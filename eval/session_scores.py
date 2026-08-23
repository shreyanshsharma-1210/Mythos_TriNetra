"""Python port of SessionScores.kt — median-of-five stabilisation for live-equivalent metrics."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

SEVERITY = {"INDETERMINATE": 0, "SAFE": 1, "SUSPICIOUS": 2, "CRITICAL": 3}

BUFFER_WINDOWS = 5
MIN_WINDOWS = 3
ESCALATE_WINDOWS = 2
DE_ESCALATE_WINDOWS = 4
MAX_HISTORY = 240


@dataclass
class WindowVerdict:
    level: str
    reason: str
    voice_similarity: float | None
    synthetic_probability: float | None
    contact_name: str | None = None


@dataclass
class WindowAnalysis:
    start_s: float
    verdict: WindowVerdict


@dataclass
class SessionScores:
    current_level: str = "INDETERMINATE"
    peak_level: str = "INDETERMINATE"
    stable_level: str = "INDETERMINATE"
    stable_peak_level: str = "INDETERMINATE"
    measured_windows: int = 0
    skipped_windows: int = 0
    history: list[WindowAnalysis] = field(default_factory=list)
    pending_level: str | None = None
    pending_count: int = 0

    def accept(
        self,
        analysis: WindowAnalysis,
        fuse_fn: Callable[[float | None, float | None], WindowVerdict],
    ) -> SessionScores:
        verdict = analysis.verdict
        measured = verdict.level != "INDETERMINATE"

        next_history = (self.history + [analysis])[-MAX_HISTORY:]
        next_peak = (
            verdict.level
            if SEVERITY[verdict.level] > SEVERITY[self.peak_level]
            else self.peak_level
        )

        stabilised = self._stabilise(next_history, fuse_fn)

        next_stable_peak = (
            stabilised.level
            if SEVERITY[stabilised.level] > SEVERITY[self.stable_peak_level]
            else self.stable_peak_level
        )

        return SessionScores(
            current_level=verdict.level if measured else self.current_level,
            peak_level=next_peak,
            stable_level=stabilised.level,
            stable_peak_level=next_stable_peak,
            measured_windows=self.measured_windows + (1 if measured else 0),
            skipped_windows=self.skipped_windows + (0 if measured else 1),
            history=next_history,
            pending_level=stabilised.pending_level,
            pending_count=stabilised.pending_count,
        )

    def _stabilise(
        self,
        history: list[WindowAnalysis],
        fuse_fn: Callable[[float | None, float | None], WindowVerdict],
    ) -> "_Stabilised":
        recent = [
            h for h in history if h.verdict.level != "INDETERMINATE"
        ][-BUFFER_WINDOWS:]

        if len(recent) < MIN_WINDOWS:
            return _Stabilised("INDETERMINATE", None, None, 0)

        similarity = _median([h.verdict.voice_similarity for h in recent])
        synthetic = _median([h.verdict.synthetic_probability for h in recent])
        contact_name = recent[-1].verdict.contact_name
        candidate = fuse_fn(similarity, synthetic)
        candidate.contact_name = contact_name

        if candidate.level == self.stable_level:
            return _Stabilised(self.stable_level, candidate, None, 0)

        if self.stable_level == "INDETERMINATE":
            return _Stabilised(candidate.level, candidate, None, 0)

        required = (
            ESCALATE_WINDOWS
            if SEVERITY[candidate.level] > SEVERITY[self.stable_level]
            else DE_ESCALATE_WINDOWS
        )
        count = self.pending_count + 1 if self.pending_level == candidate.level else 1
        if count >= required:
            return _Stabilised(candidate.level, candidate, None, 0)
        return _Stabilised(self.stable_level, None, candidate.level, count)


@dataclass
class _Stabilised:
    level: str
    verdict: WindowVerdict | None
    pending_level: str | None
    pending_count: int


def _median(values: list[float | None]) -> float | None:
    nums = [v for v in values if v is not None]
    if not nums:
        return None
    nums.sort()
    mid = len(nums) // 2
    if len(nums) % 2 == 1:
        return nums[mid]
    return (nums[mid - 1] + nums[mid]) / 2.0
