import pandas as pd 
import glob

tray_instance = ["net_definition", "surgery_id"]
instrument_key = ["surgery_id", "article_definition"] 

def load_instance(path: str) -> pd.DataFrame:
    return pd.read_csv(path)

def compute_metrics(df: pd.DataFrame, surgery_type: str | None = None) -> dict | None:
    if surgery_type is not None:
        df = df[df["treatment"] == surgery_type]
    if df.empty:
        return None
    
    n_surgeries = df["surgery_id"].nunique()
    opened = df[df["tray_opened"] == 1]

    # Number of trays opened & trays per surgeries & instruments assigned per surgery 
    trays_opened = opened.drop_duplicates(tray_instance).shape[0]
    trays_assigned = df.drop_duplicates(tray_instance).shape[0]
    avg_trays_assigned_per_surgery = trays_assigned / n_surgeries if n_surgeries else 0.0
    avg_trays_opened_per_surgery = trays_opened / n_surgeries if n_surgeries else 0.0
    avg_instruments_assigned_per_surgery = len(df) / df["surgery_id"].nunique() if n_surgeries else 0.0

    # Usage rate 
    copy_df = df
    n_instruments = len(copy_df)
    used_count = int(copy_df["used"].sum())
    usage_rate = used_count / n_instruments if n_instruments else 0.0

    # Overage and underage as done by Deshpande
    supply = copy_df.groupby(instrument_key).size().rename("supply")
    used = df.groupby(instrument_key)["used"].sum().rename("used")
    pair = pd.concat([supply, used], axis=1).fillna(0)
    surplus = pair["supply"] - pair["used"]
    overage_total = float(surplus.clip(lower=0).sum())
    underage_total = float((-surplus).clip(lower=0).sum())

    # instruments per surgery
    overage = overage_total / n_surgeries if n_surgeries else 0.0     
    underage = underage_total / n_surgeries if n_surgeries else 0.0

    # Number of unique trays and average number of instruments per tray 
    composition = df.drop_duplicates(["net_definition", "article_definition"])
    n_unique_trays = composition["net_definition"].nunique()
    avg_instruments_per_tray = (
        len(composition) / n_unique_trays if n_unique_trays else 0.0
    )

    return {
        "surgery_type": surgery_type or "ALL",
        "n_surgeries": n_surgeries,
        "usage_rate": usage_rate,
        "used_count": used_count,
        "instruments_in_denominator": n_instruments,
        "trays_assigned_total": trays_assigned,
        "trays_opened_total": trays_opened,
        "avg_trays_assigned_per_surgery": avg_trays_assigned_per_surgery,
        "avg_trays_opened_per_surgery": avg_trays_opened_per_surgery,
        "avg_instruments_assigned_per_surgery": avg_instruments_assigned_per_surgery,
        "overage_per_surgery": overage,
        "underage_per_surgery": underage,
        "overage_total": overage_total,
        "underage_total": underage_total,
        "n_unique_trays": n_unique_trays,
        "avg_instruments_per_tray": avg_instruments_per_tray,
    }

# Run the metrics over several instance files and report mean & std across them 
def aggregate_over_instances(paths, surgery_type: str | None = None) -> pd.DataFrame:
    rows = [compute_metrics(load_instance(p), surgery_type) for p in paths]
    rows = [r for r in rows if r is not None]
    table = pd.DataFrame(rows)
    keys = ["usage_rate", "overage_per_surgery", "underage_per_surgery", "trays_opened_total",
            "avg_trays_opened_per_surgery", "avg_instruments_per_tray"]
    return table[keys].agg(["mean", "std"]).T


def format_results(m: dict) -> str:
    if m is None:
        return "No rows match the selected surgery type."
    return (
        f"=== Metrics ({m['surgery_type']}) ===\n"
        f"Surgeries                        : {m['n_surgeries']}\n"
        f"Usage rate                       : {m['usage_rate']:.1%} "
        f"({m['used_count']}/{m['instruments_in_denominator']} instruments)\n"
        f"Trays assigned (total)           : {m['trays_assigned_total']}\n"
        f"Trays opened (total)             : {m['trays_opened_total']}\n"
        f"Avg trays assigned/surgery       : {m['avg_trays_assigned_per_surgery']:.2f}\n"
        f"Avg trays opened/surgery         : {m['avg_trays_opened_per_surgery']:.2f}\n"
        f"Avg instruments assigned/surgery : {m['avg_instruments_assigned_per_surgery']:.2f}\n"
        f"Overage  (instr./surgery)        : {m['overage_per_surgery']:.2f}  "
        f"(total {m['overage_total']:.0f})\n"
        f"Underage (instr./surgery)        : {m['underage_per_surgery']:.2f}  "
        f"(total {m['underage_total']:.0f})\n"
        f"Unique trays                     : {m['n_unique_trays']}\n"
        f"Avg instruments / tray           : {m['avg_instruments_per_tray']:.2f}\n"
    )

if __name__ == "__main__":
    csv_path = "data/simulatedData/instance00.csv"
    # Evaluate specific surgery type; choose "None" to evaluate all surgery types
    surgery_type = None       
    
    # Get results from one instance
    df = load_instance(csv_path)
    print(format_results(compute_metrics(df, surgery_type)))

    # Aggregate over all simulated instances and take mean / std across them
    paths = sorted(glob.glob("data/simulatedData/instance*.csv"))
    print(aggregate_over_instances(paths, surgery_type))