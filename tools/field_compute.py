"""
Magnetic field reconstruction from sparse spatial samples.

The math here mirrors the Android app's `fieldmap/` package (sample storage,
inverse-distance-weighted interpolation, RK4 field-line tracing) ported to
the server side so the phone can offload the heavy work.

Everything lives in two units:
  * `SampleStore`  — append-only spatial store with a hash grid
  * `FieldComputer` — IDW interpolator + RK4 tracer + frontier-suggestion engine

NumPy does the per-cache-cell IDW vectorised across the candidate samples
that fall inside each cell's search radius, then the result is flattened to
a nested Python list so the RK4 inner loop reads Python floats (NumPy
scalar arithmetic is too slow inside a tight per-step loop). The precompute
pays for the trilinear-cache step that used to dominate, and the line tracer
becomes essentially constant-cost per step.
"""
from __future__ import annotations

import math
import threading
import time
from dataclasses import dataclass, field
from typing import Iterable, Optional

import numpy as np


# ---------------------------------------------------------------------------
# Sample store
# ---------------------------------------------------------------------------

# Hash grid for fast neighbour lookup. We size it so a typical IDW query
# only touches a 5x5x5 cube of cells; at INTERP_RADIUS=0.5m that means
# SAMPLE_CELL=0.25m, which keeps per-cell sample lists short and the
# query loop tight (Python overhead dominates IDW arithmetic at this scale).
SAMPLE_CELL = 0.25

# RK4 step + bounds for line tracing. 5cm × 80 steps = 4m per direction,
# which covers any normal indoor mapping session.
TRACE_STEP        = 0.05
TRACE_MAX_STEPS   = 80
SEED_SPACING      = 0.5
MIN_LINE_DIST     = 0.15
COVERAGE_RADIUS   = 0.5
MIN_DENSITY       = 3
# IDW search radius. Smaller is dramatically faster (touched-cell count is
# cubic in the radius) and still gives good interpolation when samples
# are dense enough — which they are when the user is walking around.
INTERP_RADIUS     = 0.5
INTERP_EPSILON    = 0.01
INTERP_MIN_NEAR   = 2

# 30 cm voxels for the "where should the user go next" frontier search.
FRONTIER_CELL     = 0.30
FRONTIER_RANGE    = 4.0   # m — only suggest cells within this distance of the user
FRONTIER_MAX      = 8     # cap on suggestions returned per poll


@dataclass
class Sample:
    t: float                      # client-supplied timestamp (s, monotonic)
    p: tuple[float, float, float] # world position (m)
    b: tuple[float, float, float] # magnetic field vector (µT, world frame)


class SampleStore:
    """Append-only store with a uniform-grid spatial index.

    All mutators take an internal lock so the HTTP handler thread and the
    background recompute thread can share an instance without races.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._samples: list[Sample] = []
        # cell (ix, iy, iz) → list of sample indices
        self._grid: dict[tuple[int, int, int], list[int]] = {}
        self._sum = [0.0, 0.0, 0.0]
        self._min = [math.inf, math.inf, math.inf]
        self._max = [-math.inf, -math.inf, -math.inf]
        self._latest_user_pos: Optional[tuple[float, float, float]] = None
        self._generation = 0  # bumped each time samples are added

    # ------- mutators --------------------------------------------------------

    def add_batch(self, samples: Iterable[Sample]) -> int:
        """Add a batch of samples; return the new total count."""
        with self._lock:
            for s in samples:
                idx = len(self._samples)
                self._samples.append(s)
                cell = self._cell(s.p)
                self._grid.setdefault(cell, []).append(idx)
                self._sum[0] += s.b[0]
                self._sum[1] += s.b[1]
                self._sum[2] += s.b[2]
                for i in range(3):
                    if s.p[i] < self._min[i]: self._min[i] = s.p[i]
                    if s.p[i] > self._max[i]: self._max[i] = s.p[i]
                self._latest_user_pos = s.p
            self._generation += 1
            return len(self._samples)

    def reset(self) -> None:
        with self._lock:
            self._samples.clear()
            self._grid.clear()
            self._sum = [0.0, 0.0, 0.0]
            self._min = [math.inf, math.inf, math.inf]
            self._max = [-math.inf, -math.inf, -math.inf]
            self._latest_user_pos = None
            self._generation += 1

    # ------- read-only snapshots --------------------------------------------

    def snapshot(self) -> "SampleSnapshot":
        """Take a cheap, immutable snapshot for the recompute thread."""
        with self._lock:
            # We hand out shared references — the recompute thread treats
            # them as read-only. New batches always go to fresh list/dict
            # entries via add_batch above, so existing entries don't mutate.
            return SampleSnapshot(
                samples=list(self._samples),  # shallow copy; Sample is frozen-ish
                grid={k: list(v) for k, v in self._grid.items()},
                sum_=tuple(self._sum),
                min_=tuple(self._min) if self._samples else None,
                max_=tuple(self._max) if self._samples else None,
                latest_user_pos=self._latest_user_pos,
                generation=self._generation,
            )

    @property
    def generation(self) -> int:
        return self._generation

    @property
    def count(self) -> int:
        return len(self._samples)

    # ------- helpers --------------------------------------------------------

    @staticmethod
    def _cell(p: tuple[float, float, float]) -> tuple[int, int, int]:
        return (
            math.floor(p[0] / SAMPLE_CELL),
            math.floor(p[1] / SAMPLE_CELL),
            math.floor(p[2] / SAMPLE_CELL),
        )


@dataclass
class SampleSnapshot:
    samples: list[Sample]
    grid: dict[tuple[int, int, int], list[int]]
    sum_: tuple[float, float, float]
    min_: Optional[tuple[float, float, float]]
    max_: Optional[tuple[float, float, float]]
    latest_user_pos: Optional[tuple[float, float, float]]
    generation: int

    @property
    def count(self) -> int:
        return len(self.samples)

    def average_field(self) -> tuple[float, float, float]:
        if not self.samples:
            return (0.0, 0.0, 0.0)
        n = len(self.samples)
        return (self.sum_[0] / n, self.sum_[1] / n, self.sum_[2] / n)


# ---------------------------------------------------------------------------
# Interpolation + tracing + suggestion
# ---------------------------------------------------------------------------

@dataclass
class FieldLine:
    points:      list[tuple[float, float, float]]
    strengths:   list[float]   # 0..1 normalised by avg magnitude in mapped region
    confidences: list[float]   # 0..1 by local sample density


@dataclass
class FieldRepresentation:
    generation:        int
    sample_count:      int
    average_field:     tuple[float, float, float]
    average_magnitude: float
    bounds_min:        Optional[tuple[float, float, float]]
    bounds_max:        Optional[tuple[float, float, float]]
    bias:              str
    lines:             list[FieldLine]
    suggestions:       list[tuple[float, float, float]]
    user_position:     Optional[tuple[float, float, float]]
    compute_ms:        int


# Cache cell size for the lazy trilinear grid that backs `normalised`. Smaller
# = more accurate tracing; bigger = faster recompute. 10cm matches the
# typical sample-cluster scale and is plenty for visual rendering.
CACHE_CELL = 0.10


class FieldComputer:
    """Stateless computation against a `SampleSnapshot`.

    `bias` controls whether the interpolated field has the global average
    subtracted (mirrors the Android app's "Subtract Earth field" toggle).

    The IDW is the hot loop: it runs once per cache cell visited by a
    trace (lazy 10 cm cache, ~5 k cells per session). We keep the cache
    layout from the pure-Python version but vectorise the per-cell IDW
    with NumPy — for high-density data (lots of samples per IDW), this is
    a clear win; for sparse data the per-call overhead dampens the gain
    but we still come out ahead, because most of the time goes into the
    cells where the candidate set is biggest.
    """

    # Below this many candidates per IDW, the pure-Python loop wins
    # (NumPy's per-call setup overhead dominates the small-vector math).
    NUMPY_THRESHOLD = 24

    def __init__(self, snap: SampleSnapshot, bias: str = "none") -> None:
        self.snap = snap
        self.bias = bias
        avg = snap.average_field()
        self._bias_vec_t: tuple[float, float, float]
        if bias == "earth":
            self._bias_vec_t = avg
        else:
            self._bias_vec_t = (0.0, 0.0, 0.0)

        # Sample positions / fields as NumPy arrays once. Cheap relative to
        # the rest of the pipeline, and lets the per-cell IDW be pure
        # vector ops when the candidate set is large enough to benefit.
        n = len(snap.samples)
        if n > 0:
            pos = np.empty((n, 3), dtype=np.float32)
            fld = np.empty((n, 3), dtype=np.float32)
            for i, s in enumerate(snap.samples):
                pos[i, 0] = s.p[0]; pos[i, 1] = s.p[1]; pos[i, 2] = s.p[2]
                fld[i, 0] = s.b[0]; fld[i, 1] = s.b[1]; fld[i, 2] = s.b[2]
            self._sample_pos = pos
            self._sample_field = fld
        else:
            self._sample_pos = np.zeros((0, 3), dtype=np.float32)
            self._sample_field = np.zeros((0, 3), dtype=np.float32)

        # Lazy cache of interpolated field at grid points, keyed by integer
        # (ix, iy, iz). Stores `None` for "no data here" too, so we don't
        # repeatedly re-query empty regions during tracing.
        self._grid_cache: dict[tuple[int, int, int], Optional[tuple[float, float, float]]] = {}

    # ------- _count_near (used by trace confidence + seed scoring) ----------

    def _count_near(self, p: tuple[float, float, float], r: float) -> int:
        cr = int(r / SAMPLE_CELL) + 1
        cx = math.floor(p[0] / SAMPLE_CELL)
        cy = math.floor(p[1] / SAMPLE_CELL)
        cz = math.floor(p[2] / SAMPLE_CELL)
        r2 = r * r
        n = 0
        grid = self.snap.grid
        samples = self.snap.samples
        for dx in range(-cr, cr + 1):
            for dy in range(-cr, cr + 1):
                for dz in range(-cr, cr + 1):
                    bucket = grid.get((cx + dx, cy + dy, cz + dz))
                    if not bucket:
                        continue
                    for idx in bucket:
                        s = samples[idx]
                        ddx = s.p[0] - p[0]
                        ddy = s.p[1] - p[1]
                        ddz = s.p[2] - p[2]
                        if ddx * ddx + ddy * ddy + ddz * ddz <= r2:
                            n += 1
        return n

    def normalised(self, p: tuple[float, float, float]) -> Optional[tuple[float, float, float]]:
        b = self._trilinear(p)
        if b is None:
            return None
        m = math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        # Looser threshold when bias is subtracted, since local deviations
        # from Earth's field are an order of magnitude smaller than |B_earth|.
        min_mag = 0.01 if self.bias == "earth" else 0.1
        if m < min_mag:
            return None
        return (b[0] / m, b[1] / m, b[2] / m)

    def magnitude(self, p: tuple[float, float, float]) -> Optional[float]:
        b = self._trilinear(p)
        if b is None:
            return None
        return math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])

    # ------- IDW interpolation (NumPy at high candidate count) --------------

    def _gather_candidates(self, p: tuple[float, float, float]) -> list[int]:
        """Collect sample indices in the bucket-grid neighbourhood of `p`.

        Includes a margin of one bucket so we don't miss samples that sit at
        the far edge of an otherwise-out-of-range bucket.
        """
        cr = int(INTERP_RADIUS / SAMPLE_CELL) + 1
        cx = math.floor(p[0] / SAMPLE_CELL)
        cy = math.floor(p[1] / SAMPLE_CELL)
        cz = math.floor(p[2] / SAMPLE_CELL)
        out: list[int] = []
        grid = self.snap.grid
        for dx in range(-cr, cr + 1):
            for dy in range(-cr, cr + 1):
                for dz in range(-cr, cr + 1):
                    bucket = grid.get((cx + dx, cy + dy, cz + dz))
                    if bucket:
                        out.extend(bucket)
        return out

    def interpolate(self, p: tuple[float, float, float]) -> Optional[tuple[float, float, float]]:
        candidates = self._gather_candidates(p)
        n = len(candidates)
        if n < INTERP_MIN_NEAR:
            return None
        if n < self.NUMPY_THRESHOLD:
            return self._interpolate_python(p, candidates)
        return self._interpolate_numpy(p, candidates)

    def _interpolate_python(self, p: tuple[float, float, float],
                            candidates: list[int]) -> Optional[tuple[float, float, float]]:
        # Hand-tuned: read sample tuples from snap.samples directly so we
        # avoid numpy scalar overhead in the inner loop.
        samples = self.snap.samples
        r2 = INTERP_RADIUS * INTERP_RADIUS
        wx = wy = wz = wsum = 0.0
        used = 0
        px, py, pz = p
        for idx in candidates:
            s = samples[idx]
            sp = s.p
            ddx = sp[0] - px; ddy = sp[1] - py; ddz = sp[2] - pz
            d2 = ddx * ddx + ddy * ddy + ddz * ddz
            if d2 > r2: continue
            d = math.sqrt(d2)
            if d < INTERP_EPSILON: d = INTERP_EPSILON
            w = 1.0 / (d * d)
            sb = s.b
            wx += w * sb[0]; wy += w * sb[1]; wz += w * sb[2]
            wsum += w
            used += 1
        if used < INTERP_MIN_NEAR:
            return None
        bx, by, bz = self._bias_vec_t
        return (wx / wsum - bx, wy / wsum - by, wz / wsum - bz)

    def _interpolate_numpy(self, p: tuple[float, float, float],
                           candidates: list[int]) -> Optional[tuple[float, float, float]]:
        idxs = np.fromiter(candidates, dtype=np.intp, count=len(candidates))
        pos = self._sample_pos[idxs]
        center = np.array(p, dtype=np.float32)
        diff = pos - center
        d2 = (diff * diff).sum(axis=1)
        mask = d2 <= (INTERP_RADIUS * INTERP_RADIUS)
        if int(mask.sum()) < INTERP_MIN_NEAR:
            return None
        d2 = d2[mask]
        fld = self._sample_field[idxs[mask]]
        d = np.sqrt(d2)
        np.maximum(d, INTERP_EPSILON, out=d)
        w = 1.0 / (d * d)
        val = (fld * w[:, None]).sum(axis=0) / w.sum()
        bx, by, bz = self._bias_vec_t
        return (float(val[0]) - bx, float(val[1]) - by, float(val[2]) - bz)

    # ------- Lazy trilinear cache (the speed-critical inner loop) ----------

    def _grid_at(self, ix: int, iy: int, iz: int) -> Optional[tuple[float, float, float]]:
        key = (ix, iy, iz)
        cache = self._grid_cache
        if key in cache:
            return cache[key]
        p = ((ix + 0.5) * CACHE_CELL, (iy + 0.5) * CACHE_CELL, (iz + 0.5) * CACHE_CELL)
        v = self.interpolate(p)
        cache[key] = v
        return v

    def _trilinear(self, p: tuple[float, float, float]) -> Optional[tuple[float, float, float]]:
        # Convert world position to "cell-centre coordinates" so integer
        # parts index the surrounding cube of cached cells.
        u = p[0] / CACHE_CELL - 0.5
        v = p[1] / CACHE_CELL - 0.5
        w = p[2] / CACHE_CELL - 0.5
        x0 = math.floor(u); y0 = math.floor(v); z0 = math.floor(w)
        fx = u - x0; fy = v - y0; fz = w - z0

        ga = self._grid_at
        v000 = ga(x0,     y0,     z0)
        if v000 is None: return None
        v100 = ga(x0 + 1, y0,     z0)
        if v100 is None: return None
        v010 = ga(x0,     y0 + 1, z0)
        if v010 is None: return None
        v110 = ga(x0 + 1, y0 + 1, z0)
        if v110 is None: return None
        v001 = ga(x0,     y0,     z0 + 1)
        if v001 is None: return None
        v101 = ga(x0 + 1, y0,     z0 + 1)
        if v101 is None: return None
        v011 = ga(x0,     y0 + 1, z0 + 1)
        if v011 is None: return None
        v111 = ga(x0 + 1, y0 + 1, z0 + 1)
        if v111 is None: return None

        # Standard 3D linear interpolation; unrolled for speed.
        ifx = 1.0 - fx; ify = 1.0 - fy; ifz = 1.0 - fz
        c00x = v000[0] * ifx + v100[0] * fx
        c00y = v000[1] * ifx + v100[1] * fx
        c00z = v000[2] * ifx + v100[2] * fx
        c10x = v010[0] * ifx + v110[0] * fx
        c10y = v010[1] * ifx + v110[1] * fx
        c10z = v010[2] * ifx + v110[2] * fx
        c01x = v001[0] * ifx + v101[0] * fx
        c01y = v001[1] * ifx + v101[1] * fx
        c01z = v001[2] * ifx + v101[2] * fx
        c11x = v011[0] * ifx + v111[0] * fx
        c11y = v011[1] * ifx + v111[1] * fx
        c11z = v011[2] * ifx + v111[2] * fx
        c0x = c00x * ify + c10x * fy
        c0y = c00y * ify + c10y * fy
        c0z = c00z * ify + c10z * fy
        c1x = c01x * ify + c11x * fy
        c1y = c01y * ify + c11y * fy
        c1z = c01z * ify + c11z * fy
        return (c0x * ifz + c1x * fz, c0y * ifz + c1y * fz, c0z * ifz + c1z * fz)

    # ------- RK4 line trace -------------------------------------------------

    def trace_line(self, seed: tuple[float, float, float], direction: int, avg_mag: float
                   ) -> list[tuple[tuple[float, float, float], float, float]]:
        out: list[tuple[tuple[float, float, float], float, float]] = []
        p = list(seed)
        d = float(direction)
        h = TRACE_STEP
        last_conf = 1.0
        for step in range(TRACE_MAX_STEPS):
            k1 = self.normalised((p[0], p[1], p[2]))
            if k1 is None: break
            p2 = (p[0] + d * h * 0.5 * k1[0], p[1] + d * h * 0.5 * k1[1], p[2] + d * h * 0.5 * k1[2])
            k2 = self.normalised(p2)
            if k2 is None: break
            p3 = (p[0] + d * h * 0.5 * k2[0], p[1] + d * h * 0.5 * k2[1], p[2] + d * h * 0.5 * k2[2])
            k3 = self.normalised(p3)
            if k3 is None: break
            p4 = (p[0] + d * h * k3[0], p[1] + d * h * k3[1], p[2] + d * h * k3[2])
            k4 = self.normalised(p4)
            if k4 is None: break
            p[0] += d * h / 6.0 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0])
            p[1] += d * h / 6.0 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1])
            p[2] += d * h / 6.0 * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2])
            if step % 5 == 0:
                density = self._count_near((p[0], p[1], p[2]), COVERAGE_RADIUS)
                last_conf = max(0.0, min(1.0, density / MIN_DENSITY))
            mag = self.magnitude((p[0], p[1], p[2])) or 0.0
            strength = max(0.0, min(1.0, mag / avg_mag if avg_mag > 0 else 0.0))
            out.append(((p[0], p[1], p[2]), last_conf, strength))
        return out

    def average_magnitude(self, mn: tuple[float, float, float], mx: tuple[float, float, float]) -> float:
        step = SEED_SPACING * 2
        total = 0.0
        n = 0
        x = mn[0]
        while x <= mx[0]:
            y = mn[1]
            while y <= mx[1]:
                z = mn[2]
                while z <= mx[2]:
                    m = self.magnitude((x, y, z))
                    if m is not None:
                        total += m; n += 1
                    z += step
                y += step
            x += step
        return total / n if n else 0.0

    # ------- top-level reconstruction --------------------------------------

    def compute(self) -> FieldRepresentation:
        t0 = time.monotonic()
        snap = self.snap

        if snap.min_ is None or snap.max_ is None or snap.count < 5:
            return FieldRepresentation(
                generation=snap.generation,
                sample_count=snap.count,
                average_field=snap.average_field(),
                average_magnitude=0.0,
                bounds_min=snap.min_,
                bounds_max=snap.max_,
                bias=self.bias,
                lines=[],
                suggestions=[],
                user_position=snap.latest_user_pos,
                compute_ms=int((time.monotonic() - t0) * 1000),
            )

        pad = 0.3
        mn = (snap.min_[0] - pad, snap.min_[1] - pad, snap.min_[2] - pad)
        mx = (snap.max_[0] + pad, snap.max_[1] + pad, snap.max_[2] + pad)

        avg_mag = self.average_magnitude(mn, mx)
        if avg_mag < 0.01:
            return FieldRepresentation(
                generation=snap.generation,
                sample_count=snap.count,
                average_field=snap.average_field(),
                average_magnitude=avg_mag,
                bounds_min=snap.min_,
                bounds_max=snap.max_,
                bias=self.bias,
                lines=[],
                suggestions=self._frontier_suggestions(),
                user_position=snap.latest_user_pos,
                compute_ms=int((time.monotonic() - t0) * 1000),
            )

        # Walk a 3D grid of seed points. Skip a seed if it isn't within reach
        # of any sample, or if a previously-traced line already passes too
        # close to it (so we don't draw a hundred copies of the same curve).
        existing: list[tuple[float, float, float]] = []
        lines: list[FieldLine] = []
        min_seed_mag = 0.05 if self.bias == "earth" else 1.0

        x = mn[0]
        while x <= mx[0]:
            y = mn[1]
            while y <= mx[1]:
                z = mn[2]
                while z <= mx[2]:
                    seed = (x, y, z)
                    m = self.magnitude(seed)
                    if m is None or m < min_seed_mag:
                        z += SEED_SPACING; continue
                    if self._too_close(seed, existing):
                        z += SEED_SPACING; continue

                    fwd = self.trace_line(seed, +1, avg_mag)
                    bwd = self.trace_line(seed, -1, avg_mag)

                    # Stitch backward (reversed) + seed + forward into one polyline.
                    pts:    list[tuple[float, float, float]] = []
                    confs:  list[float] = []
                    strs:   list[float] = []
                    for tp, c, s in reversed(bwd):
                        pts.append(tp); confs.append(c); strs.append(s)
                    seed_density = self._count_near(seed, COVERAGE_RADIUS)
                    seed_conf = max(0.0, min(1.0, seed_density / MIN_DENSITY))
                    seed_mag = m
                    pts.append(seed)
                    confs.append(seed_conf)
                    strs.append(max(0.0, min(1.0, seed_mag / avg_mag)))
                    for tp, c, s in fwd:
                        pts.append(tp); confs.append(c); strs.append(s)

                    if len(pts) >= 5:
                        lines.append(FieldLine(points=pts, strengths=strs, confidences=confs))
                        # Sparse sampling of the line for the closeness check
                        # — full O(N²) would dominate runtime.
                        for i in range(0, len(pts), 5):
                            existing.append(pts[i])

                    z += SEED_SPACING
                y += SEED_SPACING
            x += SEED_SPACING

        return FieldRepresentation(
            generation=snap.generation,
            sample_count=snap.count,
            average_field=snap.average_field(),
            average_magnitude=avg_mag,
            bounds_min=snap.min_,
            bounds_max=snap.max_,
            bias=self.bias,
            lines=lines,
            suggestions=self._frontier_suggestions(),
            user_position=snap.latest_user_pos,
            compute_ms=int((time.monotonic() - t0) * 1000),
        )

    def _too_close(self, p: tuple[float, float, float],
                   existing: list[tuple[float, float, float]]) -> bool:
        d2 = MIN_LINE_DIST * MIN_LINE_DIST
        for q in existing:
            ddx = p[0] - q[0]
            ddy = p[1] - q[1]
            ddz = p[2] - q[2]
            if ddx * ddx + ddy * ddy + ddz * ddz < d2:
                return True
        return False

    # ------- "go this way" suggestions -------------------------------------

    def _frontier_suggestions(self) -> list[tuple[float, float, float]]:
        """Find unfilled voxels adjacent to filled ones, near the user.

        We re-bucket samples into a coarser FRONTIER_CELL grid so the
        suggestions are at a friendly walking-step scale (30 cm), not the
        20 cm sample-storage scale.
        """
        snap = self.snap
        if not snap.samples:
            return []

        # Coarse occupancy: which 30 cm cells contain at least one sample.
        occ: set[tuple[int, int, int]] = set()
        for s in snap.samples:
            occ.add((
                math.floor(s.p[0] / FRONTIER_CELL),
                math.floor(s.p[1] / FRONTIER_CELL),
                math.floor(s.p[2] / FRONTIER_CELL),
            ))

        user = snap.latest_user_pos
        user_cell = None
        if user is not None:
            user_cell = (
                math.floor(user[0] / FRONTIER_CELL),
                math.floor(user[1] / FRONTIER_CELL),
                math.floor(user[2] / FRONTIER_CELL),
            )

        # Frontier = unoccupied cells touching at least one occupied cell
        # (26-connected). We also restrict by Manhattan distance from the
        # user cell so the search doesn't fan out across the whole map.
        cell_range = int(FRONTIER_RANGE / FRONTIER_CELL) + 1
        frontier: dict[tuple[int, int, int], int] = {}  # cell → score (touching neighbours)
        for cell in occ:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        if dx == dy == dz == 0:
                            continue
                        nb = (cell[0] + dx, cell[1] + dy, cell[2] + dz)
                        if nb in occ:
                            continue
                        if user_cell is not None:
                            if (abs(nb[0] - user_cell[0]) > cell_range or
                                abs(nb[1] - user_cell[1]) > cell_range or
                                abs(nb[2] - user_cell[2]) > cell_range):
                                continue
                        frontier[nb] = frontier.get(nb, 0) + 1

        if not frontier:
            return []

        # Sort by ascending distance from the user (closest first), tie-break
        # by descending touching-neighbour count (cells with more occupied
        # neighbours are better leads — they fill in the existing map).
        ux, uy, uz = user if user is not None else (0.0, 0.0, 0.0)
        scored: list[tuple[float, int, tuple[float, float, float]]] = []
        for cell, score in frontier.items():
            cx = (cell[0] + 0.5) * FRONTIER_CELL
            cy = (cell[1] + 0.5) * FRONTIER_CELL
            cz = (cell[2] + 0.5) * FRONTIER_CELL
            d = math.sqrt((cx - ux) ** 2 + (cy - uy) ** 2 + (cz - uz) ** 2)
            if d > FRONTIER_RANGE:
                continue
            scored.append((d, -score, (cx, cy, cz)))
        scored.sort()
        return [s[2] for s in scored[:FRONTIER_MAX]]


# ---------------------------------------------------------------------------
# Per-session container
# ---------------------------------------------------------------------------

class FieldSession:
    """Owns a SampleStore, a recompute thread, and the latest representation."""

    def __init__(self, session_id: str) -> None:
        self.id = session_id
        self.store = SampleStore()
        self.bias = "none"
        self.created_at = time.time()
        self.last_seen = time.time()

        self._cv = threading.Condition()
        self._dirty = False
        self._stop = False
        self._latest: Optional[FieldRepresentation] = None
        self._latest_gen_computed = -1

        self._worker = threading.Thread(
            target=self._loop, name=f"field-recompute-{session_id}", daemon=True
        )
        self._worker.start()

    # ------- API --------------------------------------------------------

    def set_bias(self, bias: str) -> None:
        with self._cv:
            if bias != self.bias:
                self.bias = bias
                self._dirty = True
                self._cv.notify()

    def add_samples(self, samples: list[Sample]) -> int:
        n = self.store.add_batch(samples)
        with self._cv:
            self._dirty = True
            self._cv.notify()
        self.last_seen = time.time()
        return n

    def reset(self) -> None:
        self.store.reset()
        with self._cv:
            self._latest = None
            self._latest_gen_computed = -1
            self._dirty = True
            self._cv.notify()

    def representation(self) -> Optional[FieldRepresentation]:
        with self._cv:
            return self._latest

    def shutdown(self) -> None:
        with self._cv:
            self._stop = True
            self._cv.notify()

    # ------- worker loop ------------------------------------------------

    def _loop(self) -> None:
        while True:
            with self._cv:
                while not self._dirty and not self._stop:
                    self._cv.wait()
                if self._stop:
                    return
                self._dirty = False
                bias = self.bias

            snap = self.store.snapshot()
            # Avoid recomputing if nothing has actually changed and the bias
            # is unchanged from the last computation we have on hand.
            if (self._latest is not None
                    and self._latest.generation == snap.generation
                    and self._latest.bias == bias):
                continue

            try:
                rep = FieldComputer(snap, bias=bias).compute()
            except Exception as exc:  # pragma: no cover — never crash the worker
                import traceback, sys
                sys.stderr.write(
                    f"[field-recompute-{self.id}] failed: {exc}\n"
                    f"{traceback.format_exc()}\n"
                )
                continue
            with self._cv:
                self._latest = rep
                self._latest_gen_computed = rep.generation


# ---------------------------------------------------------------------------
# JSON serialisation helpers
# ---------------------------------------------------------------------------

def line_to_json(line: FieldLine) -> dict:
    # Flatten polylines for compactness — three.js BufferGeometry just wants
    # a flat float array anyway, so emitting one is friendlier than nested
    # triplets and ~3x smaller on the wire.
    pts: list[float] = []
    for p in line.points:
        pts.append(p[0]); pts.append(p[1]); pts.append(p[2])
    return {
        "points":      pts,
        "strengths":   line.strengths,
        "confidences": line.confidences,
    }


def representation_to_json(rep: FieldRepresentation) -> dict:
    sugg: list[list[float]] = [[t[0], t[1], t[2]] for t in rep.suggestions]
    return {
        "generation":      rep.generation,
        "sampleCount":     rep.sample_count,
        "averageField":    list(rep.average_field),
        "averageMagnitude": rep.average_magnitude,
        "boundsMin":       list(rep.bounds_min) if rep.bounds_min else None,
        "boundsMax":       list(rep.bounds_max) if rep.bounds_max else None,
        "bias":            rep.bias,
        "lines":           [line_to_json(l) for l in rep.lines],
        "suggestions":     sugg,
        "userPosition":    list(rep.user_position) if rep.user_position else None,
        "computeMs":       rep.compute_ms,
    }
