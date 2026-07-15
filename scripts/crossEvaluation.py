import glob 
import os
import pandas as pd
 
instrument_key = ["surgery_id", "article_definition"]
tray_instance = ["net_definition", "surgery_id"]
 
def load_instance(path: str) -> pd.DataFrame:
    return pd.read_csv(path, keep_default_na=False, dtype=str)
 
 
def _pairs(df: pd.DataFrame) -> set:
    """Set of (surgery_id, article_definition) tuples present in df."""
    return set(map(tuple, df.drop_duplicates(instrument_key)[instrument_key].itertuples(index=False, name=None)))
 
def _counts(df: pd.DataFrame) -> pd.Series:
    """Count of rows (copies) per (surgery_id, article_definition)."""
    return df.groupby(instrument_key).size()

def compute_cross_metrics(original: pd.DataFrame, reduced: pd.DataFrame,
                          surgery_type: str | None = None) -> dict | None:
    if surgery_type is not None:
        original = original[original["treatment"] == surgery_type]
        reduced = reduced[reduced["treatment"] == surgery_type]
    if original.empty:
        return None
 
    """
    # demand: pairs USED in the original
    demand = _pairs(original[original["used"].astype(int) != 0])
    # supply: pairs ASSIGNED in the reduced instance (any row, opened or not)
    supply = _pairs(reduced)
    
    underage_total = len(demand - supply)   # used originally, missing now
    overage_total = len(supply - demand)    # assigned now, never used originally 
    
    # Now we only count for opened trays, since unopened trays should not matter; but this does not fix the discrepancy between Java & Python
    # demand = _counts(original[original["used"].astype(int) != 0])
    # reduced_opened = reduced[reduced["tray_opened"].astype(int) == 1]
    # supply = _counts(reduced_opened)
    
    # demand = used COPIES in original; supply = assigned COPIES in reduced
    demand = _counts(original[original["used"].astype(int) != 0])
    supply = _counts(reduced)

    # Align on the union of (surgery, article) keys, fill missing with 0
    all_keys = demand.index.union(supply.index)
    d = demand.reindex(all_keys, fill_value=0)
    s = supply.reindex(all_keys, fill_value=0)
 
    underage_total = int((d - s).clip(lower=0).sum())  
    overage_total  = int((s - d).clip(lower=0).sum())  
    """

    """
    # Overage + underage only over opened trays
    reduced_opened = reduced[reduced["tray_opened"].astype(int) == 1].copy()
    reduced_opened["used"] = reduced_opened["used"].astype(int)   # convert ONCE, vectorised

    supply = reduced_opened.groupby(instrument_key).size()
    used_r = reduced_opened.groupby(instrument_key)["used"].sum()   # built-in C aggregation, no lambda
    pair = pd.concat([supply.rename("supply"), used_r.rename("used")], axis=1).fillna(0)
    overage_total = float((pair["supply"] - pair["used"]).clip(lower=0).sum()) 

    # Underage: needs the original to catch REMOVED instruments
    demand = _counts(original[original["used"].astype(int) != 0])
    supply_all = _counts(reduced_opened)
    all_keys = demand.index.union(supply_all.index)
    d = demand.reindex(all_keys, fill_value=0)
    s = supply_all.reindex(all_keys, fill_value=0)
    underage_total = int((d - s).clip(lower=0).sum())
    """
    # Convert numeric columns once (vectorised)
    original = original.copy()
    reduced = reduced.copy()
    original["used"] = original["used"].astype(int)
    reduced["used"] = reduced["used"].astype(int)
    original["tray_opened"] = original["tray_opened"].astype(int)
    reduced["tray_opened"] = reduced["tray_opened"].astype(int)

    # Overage: ALL trays (opened or not) — supply present minus used, per (surgery, instrument)
    supply = reduced.groupby(instrument_key).size()
    used_r = reduced.groupby(instrument_key)["used"].sum()
    pair = pd.concat([supply.rename("supply"), used_r.rename("used")], axis=1).fillna(0)
    overage_total = float((pair["supply"] - pair["used"]).clip(lower=0).sum())

    # Underage: needs the original to catch REMOVED instruments (all trays)
    demand = _counts(original[original["used"] != 0])
    supply_all = _counts(reduced)                       # was reduced_opened (undefined) -> FIXED
    all_keys = demand.index.union(supply_all.index)
    d = demand.reindex(all_keys, fill_value=0)
    s = supply_all.reindex(all_keys, fill_value=0)
    underage_total = int((d - s).clip(lower=0).sum())

    n_surgeries = original["surgery_id"].nunique()
    underage_per_surgery = underage_total / n_surgeries if n_surgeries else 0.0
    overage_per_surgery = overage_total / n_surgeries if n_surgeries else 0.0
 
    n_surgeries = original["surgery_id"].nunique()
    underage_per_surgery = underage_total / n_surgeries if n_surgeries else 0.0
    overage_per_surgery = overage_total / n_surgeries if n_surgeries else 0.0

    # Also get the number of opened trays 
    opened_original = original[original["tray_opened"].astype(int) == 1]
    opened_reduced = reduced[reduced["tray_opened"].astype(int) == 1]

    # Number of trays opened 
    trays_opened_original = opened_original.drop_duplicates(tray_instance).shape[0] / n_surgeries if n_surgeries else 0.0
    trays_opened_reduced = opened_reduced.drop_duplicates(tray_instance).shape[0] / n_surgeries if n_surgeries else 0.0
    trays_opened_diff = trays_opened_reduced - trays_opened_original

    # Usage rate 
    n_reduced = len(reduced)
    usage_rate = reduced["used"].astype(int).sum() / n_reduced if n_reduced else 0.0
 
    return {
        "surgery_type": surgery_type or "ALL",
        "underage_total": underage_total,
        "overage_total": overage_total,
        "underage_per_surgery": underage_per_surgery,
        "overage_per_surgery": overage_per_surgery,
        "trays_opened": trays_opened_reduced,
        "trays_opened_diff": trays_opened_diff,
        "usage_rate": usage_rate
    }
 
"""
def format_cross(m: dict) -> str:
    if m is None:
        return "No rows match the selected surgery type."
    return (
        f"=== Cross metrics vs original ({m['surgery_type']}) ===\n"
        f"Underage (missing, total)  : {m['underage_total']}  "
        f"({m['underage_per_surgery']:.2f}/surgery)\n"
        f"Overage  (unused, total)   : {m['overage_total']}  "
        f"({m['overage_per_surgery']:.2f}/surgery)\n"
        f"Trays opened : {m['overage_per_surgery']}"
    )
    """
 
 
def original_for(reduced_filename: str, original_dir: str) -> str:
    """
    Map a reduced filename to its original by taking the prefix before the first
    '_'. e.g. 'instance07_singleton_lt20.csv' -> '<original_dir>/instance07.csv'.
    """
    base = os.path.basename(reduced_filename).split("_", 1)[0]
    return os.path.join(original_dir, base + ".csv")


def variant_of(reduced_filename: str) -> str:
    """
    The reduction variant = filename minus the instanceNN prefix and the extension.
    e.g. 'instance07_singleton_lt20.csv' -> 'singleton_lt20'. All instances sharing
    a variant are aggregated together.
    """
    name = os.path.splitext(os.path.basename(reduced_filename))[0]
    parts = name.split("_", 1)
    return parts[1] if len(parts) > 1 else name
 
 
def evaluate_all(reduced_dir: str, original_dir: str,
                 surgery_type: str | None = None) -> pd.DataFrame:
    """
    For every reduced CSV in reduced_dir, pair it with its own original (matched by
    the instanceNN prefix) and compute cross metrics. Returns one row per reduced
    file. Originals are cached so each is read once.
    """
    reduced_paths = sorted(glob.glob(os.path.join(reduced_dir, "*.csv")))
    cache: dict[str, pd.DataFrame] = {}
    rows = []

    nInstance = 0
 
    for rp in reduced_paths:
        nInstance = nInstance + 1
        # print(f"Current run: {nInstance}")
        op = original_for(rp, original_dir)
        if not os.path.exists(op):
            print(f"  [skip] no original for {os.path.basename(rp)} "
                  f"(expected {os.path.basename(op)})")
            continue
 
        original = cache.get(op)
        if original is None:
            original = load_instance(op)
            cache[op] = original
        reduced = load_instance(rp)
 
        m = compute_cross_metrics(original, reduced, surgery_type)
        if m is None:
            continue
        m = dict(m)
        m["reduced_file"] = os.path.basename(rp)
        m["original_file"] = os.path.basename(op)
        m["variant"] = variant_of(rp)
        rows.append(m)
 
    return pd.DataFrame(rows)

def aggregate_by_variant(table: pd.DataFrame) -> pd.DataFrame:
    """
    Mean & std of the per-surgery metrics, grouped by reduction variant. One row per
    variant (e.g. delete_lt10, delete_lt20, singleton_lt10, singleton_lt20),
    aggregated across all instances of that variant.
    """
    metrics = ["underage_per_surgery", "overage_per_surgery",
                "trays_opened", "trays_opened_diff", "usage_rate"]
    grouped = table.groupby("variant")[metrics].agg(["mean", "std"])
    # n of instances contributing to each variant
    # grouped[("n_instances", "")] = table.groupby("variant").size()
    return grouped
 
 
if __name__ == "__main__":
    reduced_dir = "data/TraysKnown/results/trayReduction"
    original_dir = "data/TraysKnown/simulatedData"
    surgery_type = None   # or a treatment string to filter
 
    print("Evaluating tray reduction method:")
    table = evaluate_all(reduced_dir, original_dir, surgery_type)
 
    if table.empty:
        print("No reduced/original pairs found.")
    else:
        # Per-file results.
        # cols = ["reduced_file", "variant", "underage_total", "underage_per_surgery","overage_total", "overage_per_surgery","trays_opened", "trays_opened_diff"]
        # print(table[cols].to_string(index=False))
 
        # Final table: mean / std per reduction variant, across all instances.
        print("\n--Mean & std across instances--")
        print(aggregate_by_variant(table).to_string())