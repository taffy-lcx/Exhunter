#!/usr/bin/env python3
"""All-in-one evaluation: pick one method's output, compute every metric and
compare against the baselines.

Metrics
  Detection (micro)          — does the tool decide a method needs exception
                               handling? P/R/F1 over all samples; the positive
                               class is "developer added a try-catch in this
                               commit" (label=1).
  Catch-type (macro)         — among flagged methods, do the caught exception
                               types match the developer's added catch types?
  Repair localization (macro)— do the statements wrapped in try match the
                               developer's? Per-method try-body fingerprint
                               F1, averaged over methods.
                               Reported as Precision / Coverage / F1 / IoU, where
                               Coverage = fraction of the developer's threatened
                               statements that the method's try covers.

Usage
  python data/eval_all.py --ours <method-key> [--md out.md]
  # <method-key> is a key in the METHODS dict below (e.g. ours-deepseek-3stage).
  # Without --md, the report is printed to stdout.
"""
import argparse
import json
import sys
from collections import Counter
from pathlib import Path


# ====== method registry: method-key -> output JSON path (relative to project root) ======
METHODS = {
    # Our 3-stage method (triage -> analyzer -> repairer), threshold 0.2.
    "ours-qwen-3stage":     "data/qwen3-max-3stage/output_0.2.json",
    "ours-deepseek-3stage": "data/deepseek-v4-flash-3stage/output_0.2.json",
    "ours-gpt-4o-3stage":   "data/gpt-4o-3stage/output_0.2.json",
    # Baselines.
    "seeker-gpt-4o": "baselines/seeker/seeker_gpt-4o_output.json",   # Seeker (ASE'23)
    "neurex":        "baselines/Neurex/neurex_output.json",          # Neurex
    "kpc":           "baselines/KPC/kpc_gpt-3.5-turbo_output.json",  # KPC (ASE'23)
    "fuzzycatch":    "baselines/exassist_repo/exassist_output.json", # FuzzyCatch (ICSME'19)
    "nexgen-orig":   "baselines/nexgen/nexgen_output.json",          # NexGen
    "direct-prompt": "data/zero-shot-prompt/output.json",            # single-LLM-call baseline (former zero-shot)
}


# ====== detection signal criteria ======
ALLOWED_NATIVE_SOURCES = {
    "neurex.cls_argmax(raw_output.changed)",
    "nexgen.task1_any_pos(per_line_pred)",
    "kpc.checker_loop(result_differs)",
    "fuzzycatch.predictor_threshold(result_differs)",
}
DETECTION_TARGETS = ["nexgen-orig", "neurex", "kpc", "fuzzycatch", "direct-prompt"]   # ours added separately

# ====== Repair-metric comparison targets + each method's TP definition ======
# Each method is scored on its OWN true-positive subset (not a shared ours-TP).
# Fair: a method is evaluated only on the samples it itself flagged as needing a
# fix, and the subset does not shift when ours changes.
#
# TP definition:
#   ours-*        : label=1 and needLLM == 'yes'
#   seeker-gpt-4o : label=1                    (its Handler always runs, attempts every label=1)
#   kpc           : label=1 and methodResult != methodBefore (checker decided to change it)
#   fuzzycatch    : label=1 and methodResult != methodBefore (predictor scored high enough to wrap)
#   nexgen        : label=1 and nativeDetected==True (task1 says yes; parse-fail counts too)
#   neurex          : excluded from repair metrics (TP subset too small, ~17)
REPAIR_TARGETS = ["seeker-gpt-4o", "kpc", "fuzzycatch", "nexgen-orig", "direct-prompt"]


def own_tp_keys(name, data):
    """A method's own true-positive subset (label=1 and the method's own 'needs-fix' signal)."""
    out = set()
    for x in data:
        if x.get("label") != 1: continue
        # signal precedence: needLLM (ours) > nativeDetected (kpc/fuzzy/nexgen/neurex) > forced-all (seeker)
        if name.startswith("ours-") or name == "direct-prompt":
            if (x.get("needLLM") or "").strip().lower() == "yes":
                out.add(_sample_key(x))
        elif name.startswith("seeker"):
            out.add(_sample_key(x))   # seeker attempts a fix on every label=1
        else:
            # kpc/fuzzycatch/nexgen/neurex use nativeDetected (backfilled by refill_native_detection)
            if x.get("nativeDetected") is True:
                out.add(_sample_key(x))
    return out


def detection_signal(item):
    src = item.get("nativeSource") or ""
    if "nativeDetected" in item and item["nativeDetected"] is not None and src in ALLOWED_NATIVE_SOURCES:
        return bool(item["nativeDetected"])
    need = (item.get("needLLM") or "").strip().lower()
    if need in ("yes", "no"):
        return need == "yes"
    if need == "error":
        return False  # explicitly errored -> treated as "no fix needed"
    return None


def eval_detection(data):
    tp = fp = fn = tn = excl = nosig = 0
    for x in data:
        lbl = x.get("label", -1)
        if lbl not in (0, 1):
            excl += 1; continue
        pred = detection_signal(x)
        if pred is None:
            nosig += 1; continue
        if lbl == 1 and pred: tp += 1
        elif lbl == 1: fn += 1
        elif pred: fp += 1
        else: tn += 1
    n = tp + fp + fn + tn
    P = tp / (tp + fp) if (tp + fp) else 0
    R = tp / (tp + fn) if (tp + fn) else 0
    F1 = 2 * P * R / (P + R) if (P + R) else 0
    Acc = (tp + tn) / n if n else 0
    return dict(TP=tp, FP=fp, FN=fn, TN=tn, P=P, R=R, F1=F1, Acc=Acc, n=n, excl=excl, no_signal=nosig)


# ====== Exception-type (catch-type set difference, simple-name normalized) ======
def _norm_type(t):
    """Normalize: take the simple name after the last '.' and lowercase it, so
    'java.lang.NullPointerException' and 'NullPointerException' match equivalently."""
    return t.rsplit(".", 1)[-1].strip().lower()


def _norm_type_set(items):
    """Split multi-catch on '|', then simple-name normalize."""
    out = set()
    for e in items or []:
        for p in str(e).split("|"):
            p = _norm_type(p)
            if p:
                out.add(p)
    return out


def _new_catch_types(it):
    """Catch-type difference = all catch types in methodAfter/Result minus the catch
    types already present in methodBefore. Uses only the direct catch-type fields
    produced by ReanalyzeOutput."""
    truth_raw = it.get("exceptionTypesAll") or []
    pred_raw  = it.get("resultExceptionTypesAll") or []
    before_t  = _norm_type_set(it.get("beforeExceptionTypes") or [])
    return _norm_type_set(truth_raw) - before_t, _norm_type_set(pred_raw) - before_t


def _sample_key(it):
    return (it.get("repo_id"), it.get("patch"),
            it.get("methodName") or it.get("method_name"))


def eval_repair(data, subset_keys):
    by_key = {_sample_key(x): x for x in data}
    type_hit = 0
    # ---- catch-type: MACRO — per-method P/R/F1 averaged over methods that have a
    # ground-truth new catch type (consistent with the macro repair-localization below).
    ct_total = 0; ct_p_s = ct_r_s = ct_f_s = 0.0
    raw_total = 0; raw_cov_s = raw_prec_s = raw_iou_s = raw_f1_s = 0.0; raw_skip = 0
    subset_have = 0

    for k in subset_keys:
        it = by_key.get(k)
        if it is None: continue
        subset_have += 1
        # Does not use the legacy 'changed' field. Pure fingerprint channel;
        # an empty field scores 0.
        # ---- catch-type (macro): use resultExceptionTypesAll (after the set difference) ----
        true_t, pred_t = _new_catch_types(it)
        if pred_t and (true_t & pred_t):
            type_hit += 1
        if true_t:  # only methods with a ground-truth new catch type enter the macro average
            inter = len(true_t & pred_t)
            p = inter / len(pred_t) if pred_t else 0
            r = inter / len(true_t)
            f = 2 * p * r / (p + r) if (p + r) else 0
            ct_total += 1; ct_p_s += p; ct_r_s += r; ct_f_s += f

        truth_raw = Counter(it.get("afterTargetTryBodyFps") or [])
        pred_raw  = Counter(it.get("resultRawTryBodyFps") or [])
        if not truth_raw:
            raw_skip += 1
        else:
            inter = sum((truth_raw & pred_raw).values())
            union = sum((truth_raw | pred_raw).values())
            ts = sum(truth_raw.values()); ps = sum(pred_raw.values())
            cov = inter/ts if ts else 0
            prec = inter/ps if ps else 0
            iou = inter/union if union else 0
            f1 = 2*prec*cov/(prec+cov) if (prec+cov) else 0
            raw_total += 1
            raw_cov_s += cov; raw_prec_s += prec; raw_iou_s += iou; raw_f1_s += f1

    # macro catch-type: mean of per-method P/R/F1
    P_t = ct_p_s/ct_total if ct_total else 0
    R_t = ct_r_s/ct_total if ct_total else 0
    F1_t = ct_f_s/ct_total if ct_total else 0
    return dict(
        subset_have=subset_have,
        type_hit=type_hit, type_hit_rate=type_hit/subset_have if subset_have else 0,
        ct_total=ct_total, P_t=P_t, R_t=R_t, F1_t=F1_t,
        raw_total=raw_total, raw_skip=raw_skip,
        raw_cov=raw_cov_s/raw_total if raw_total else 0,
        raw_prec=raw_prec_s/raw_total if raw_total else 0,
        raw_iou=raw_iou_s/raw_total if raw_total else 0,
        raw_f1=raw_f1_s/raw_total if raw_total else 0,
    )


def render(ours_name, det_rows, rep_rows, inter_rows=None, inter_size=0):
    """Render metric rows as Markdown."""
    out = []
    out.append(f"# Evaluation — {ours_name}\n")
    out.append("## Metrics\n")
    out.append("- **Detection** (micro): does the tool decide a method needs exception "
               "handling? Positive class = developer added a try-catch in this commit (label=1).")
    out.append("- **Catch-type** (macro): among flagged methods, do the caught exception "
               "types match the developer's added catch types?")
    out.append("- **Repair localization** (macro): do the statements wrapped in try match the "
               "developer's? Per-method try-body statement-fingerprint overlap, averaged over "
               "methods. Reported as Precision / Coverage / F1 / IoU, where Coverage = fraction "
               "of the developer's threatened statements that the method's try covers.\n")
    # === 1. Detection ===
    ours_m = det_rows[0][1]
    out.append("## 1. Detection\n")
    out.append("| method | P | Recall | F1 | Acc |")
    out.append("|---|---:|---:|---:|---:|")
    for name, m in sorted(det_rows, key=lambda r: -r[1]["F1"]):
        out.append(f"| `{name}` | {m['P']:.4f} | {m['R']:.4f} | {m['F1']:.4f} | {m['Acc']:.4f} |")
    out.append("")
    # === 2. Repair quality, each method on its own TP subset ===
    out.append("## 2. Repair quality (each method on its own true-positive subset)\n")
    out.append("TP_n = size of each method's true-positive subset (label=1 ∧ the method "
               "flagged it as needing a fix). neurex is excluded here (TP_n too small).\n")
    out.append("### 2a. Catch-type\n")
    out.append("| method | TP_n | P | Recall | F1 |")
    out.append("|---|---:|---:|---:|---:|")
    for name, n, m in sorted(rep_rows, key=lambda r: -r[2]["F1_t"]):
        out.append(f"| `{name}` | {n} | {m['P_t']:.4f} | {m['R_t']:.4f} | {m['F1_t']:.4f} |")
    out.append("")
    out.append("### 2b. Repair localization\n")
    out.append("| method | TP_n | P | Coverage | F1 | IoU |")
    out.append("|---|---:|---:|---:|---:|---:|")
    for name, n, m in sorted(rep_rows, key=lambda r: -r[2]["raw_f1"]):
        out.append(f"| `{name}` | {n} | {m['raw_prec']:.4f} | {m['raw_cov']:.4f} | "
                   f"{m['raw_f1']:.4f} | {m['raw_iou']:.4f} |")
    # === 3. Common subset ===
    if inter_rows and inter_size > 0:
        out.append("")
        out.append(f"## 3. Repair quality on the common subset ({inter_size} methods flagged by all)\n")
        out.append("### 3a. Catch-type\n")
        out.append("| method | P | Recall | F1 |")
        out.append("|---|---:|---:|---:|")
        for name, n, m in sorted(inter_rows, key=lambda r: -r[2]["F1_t"]):
            out.append(f"| `{name}` | {m['P_t']:.4f} | {m['R_t']:.4f} | {m['F1_t']:.4f} |")
        out.append("")
        out.append("### 3b. Repair localization\n")
        out.append("| method | P | Coverage | F1 | IoU |")
        out.append("|---|---:|---:|---:|---:|")
        for name, n, m in sorted(inter_rows, key=lambda r: -r[2]["raw_f1"]):
            out.append(f"| `{name}` | {m['raw_prec']:.4f} | {m['raw_cov']:.4f} | "
                       f"{m['raw_f1']:.4f} | {m['raw_iou']:.4f} |")
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ours", default="ours-needv3-0.3")
    ap.add_argument("--md", default="")
    args = ap.parse_args()

    if args.ours not in METHODS:
        ap.error(f"--ours '{args.ours}' not in METHODS. choices: {sorted(METHODS)}")

    # ===== 1. Detection =====
    ours_data = json.load(open(METHODS[args.ours]))
    det_rows = [(args.ours, eval_detection(ours_data))]
    for tn in DETECTION_TARGETS:
        if tn in METHODS and Path(METHODS[tn]).exists():
            det_rows.append((tn, eval_detection(json.load(open(METHODS[tn])))))

    # ===== 2. Repair: each method on its own TP subset =====
    rep_rows = []
    method_data = {}
    method_tps = {}
    for tn in [args.ours] + REPAIR_TARGETS:
        if tn not in METHODS or not Path(METHODS[tn]).exists():
            continue
        d = json.load(open(METHODS[tn]))
        tp_keys = own_tp_keys(tn, d)
        method_data[tn] = d
        method_tps[tn] = tp_keys
        rep_rows.append((tn, len(tp_keys), eval_repair(d, tp_keys)))

    
    
    big_methods = [m for m in method_tps if m not in ("neurex", "direct-prompt")]
    inter_keys = set.intersection(*[method_tps[m] for m in big_methods])
    inter_rows = []
    for tn in big_methods:
        inter_rows.append((tn, len(inter_keys), eval_repair(method_data[tn], inter_keys)))

    txt = render(args.ours, det_rows, rep_rows, inter_rows, len(inter_keys))
    print(txt)
    if args.md:
        Path(args.md).write_text(txt, encoding="utf-8")
        print(f"\nmarkdown written: {args.md}")


if __name__ == "__main__":
    main()
