import glob
import os
import pandas as pd
import time
 
HEADER = ["net_definition", "net_name", "article_definition", "article_name",
          "cluster", "treatment", "treatment_code", "surgery_id", "tray_opened", "used"]
 
instrument_key = ["surgery_id", "article_definition"]
tray_instance  = ["net_definition", "surgery_id"]
 

def load_instance(path: str) -> pd.DataFrame:
    return pd.read_csv(path, keep_default_na=False, dtype=str)
 
 
def flagged_articles(df: pd.DataFrame, cutoff: float) -> set[str]:
    # Determine which articles are strictly below given cutogg
    used_int = df["used"].astype(int)
 
    # Surgeries each article is assigned to 
    assigned = df.groupby("article_definition")["surgery_id"].nunique()
 
    # surgeries each article is used in (used != 0)
    used_rows = df[used_int != 0]
    used = used_rows.groupby("article_definition")["surgery_id"].nunique()
 
    rate = (used.reindex(assigned.index, fill_value=0) / assigned)

    # Return set of flagged articles 
    return set(rate.index[rate < cutoff])
 
def demand_pairs_of(df: pd.DataFrame) -> set:
    """Set of (surgery_id, article_definition) pairs USED in the ORIGINAL df
    (used != 0). This is the demand the recomputed tray_opened is measured against."""
    df_used = df[df["used"].astype(int) != 0]
    return set(map(tuple, df_used[instrument_key].itertuples(index=False, name=None)))
 
 
def recompute_tray_opened(out_df: pd.DataFrame, demand_pairs: set) -> pd.DataFrame:
    out_df = out_df.copy()
 
    if out_df.empty:
        return out_df
 
    # surviving rows that are (surgery, article) pairs which were used originally
    pairs = list(zip(out_df["surgery_id"], out_df["article_definition"]))
    is_used_pair = pd.Series([p in demand_pairs for p in pairs], index=out_df.index)
 
    # trays (net_definition, surgery_id) holding >=1 used pair -> opened
    opened_trays = set(map(tuple,
        out_df.loc[is_used_pair]
              .drop_duplicates(tray_instance)[tray_instance]
              .itertuples(index=False, name=None)))
 
    tray_ids = list(zip(out_df["net_definition"], out_df["surgery_id"]))
    out_df["tray_opened"] = ["1" if t in opened_trays else "0" for t in tray_ids]
    return out_df 
 
def write_delete(df: pd.DataFrame, out_path: str, flagged: set[str], demand_pairs: set) -> None:
    # Drop every row with a flagged article 
    kept = df[~df["article_definition"].isin(flagged)]
    
    # Recompute tray_opened
    kept = recompute_tray_opened(kept, demand_pairs)

    kept.to_csv(out_path, index=False)
 
 
def write_singleton(df: pd.DataFrame, out_path: str, flagged: set[str], demand_pairs: set) -> None:
    # Put all flagged items in a new, single tray; assign to the same surgery and keep the same used as before 
    kept = df[~df["article_definition"].isin(flagged)]
 
    flagged_rows = df[df["article_definition"].isin(flagged)].copy()
    new_rows = []
    # For each (surgery, article) among flagged rows, was it ever used?
    flagged_rows["used_int"] = flagged_rows["used"].astype(int)
    grp = flagged_rows.groupby(["surgery_id", "article_definition"], sort=False)
    for (surgery, article), g in grp:
        if (g["used_int"] != 0).any():
            # representative row for descriptive fields: a used one
            src = g[g["used_int"] != 0].iloc[0]
            new_rows.append({
                "net_definition":  f"SINGLE_{article}",
                "net_name":        f"SINGLETON TRAY {article}",
                "article_definition": article,
                "article_name":    src.get("article_name", ""),
                "cluster":         src.get("cluster", ""),
                "treatment":       src.get("treatment", ""),
                "treatment_code":  src.get("treatment_code", ""),
                "surgery_id":      surgery,
                "tray_opened":     "1",
                "used":            "1",
            })
 
    if new_rows:
        out = pd.concat([kept, pd.DataFrame(new_rows, columns=HEADER)], ignore_index=True)
    else:
        out = kept
    
    # Recompute opened_trays
    out = recompute_tray_opened(out, demand_pairs)
    out.to_csv(out_path, index=False)
 
 
def build_reduced_instances(original_csv: str, out_dir: str) -> dict:
    os.makedirs(out_dir, exist_ok=True)
    df = load_instance(original_csv)
    n_instruments = df["article_definition"].nunique()
    base = os.path.splitext(os.path.basename(original_csv))[0]
    wp0_dir = "data/results/wp0"

    # Used pairs from original data 
    demand_pairs = demand_pairs_of(df)

    timings = {}
    for cutoff in (0.10, 0.20):
        start_time_flagging = time.time()
        flagged = flagged_articles(df, cutoff)
        pct = round(cutoff * 100)

        flagging_time = time.time() - start_time_flagging

        del_path = os.path.join(out_dir, f"{base}_delete_{pct}.csv")
        sgl_path = os.path.join(out_dir, f"{base}_movesingle_{pct}.csv")
 
        start_time_delete = time.time()
        write_delete(df, del_path, flagged, demand_pairs)
        total_time_delete = (time.time() - start_time_delete) + flagging_time

        start_time_move = time.time()
        write_singleton(df, sgl_path, flagged, demand_pairs)
        total_time_move = (time.time() - start_time_move) + flagging_time
 
        print(f"Cutoff <{pct}%: flagged {len(flagged)} / {n_instruments} instruments")
        print(f"Runtime delete: %s seconds " % total_time_delete)
        print(f"Runtime move: %s seconds " % total_time_move)

        timings[f"delete_{pct}"] = total_time_delete
        timings[f"move_{pct}"] = total_time_move
    return timings 
 
if __name__ == "__main__":
    out_dir = "data/TraysKnown/results/trayReduction"
    paths = sorted(glob.glob("data/TraysKnown/simulatedData/instance*.csv"))
    if not paths:
        print("No instance files found.")

    all_timings = []
    for p in paths:
        print(f"Processing {p}")
        all_timings.append(build_reduced_instances(p, out_dir))

    if all_timings:
        print("\n=== Average runtimes across all instances ===")
        for key in ("delete_10", "delete_20", "move_10", "move_20"):
            avg = sum(t[key] for t in all_timings) / len(all_timings)
            print(f"Average {key}: {avg} seconds")
