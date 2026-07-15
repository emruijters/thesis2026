import pandas as pd 
import numpy as np
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

    # Check unique treatment_code, maybe drop all surgeries that don't have unique treatment_code?
    """
    print(f"Number of surgeries:  {n_surgeries}")
    df_treatment = df[df["treatment_code"].notna()]
    n_surgeries_treatmentcode = df_treatment["surgery_id"].nunique()
    print(f"Number of surgeries with treatment code:  {n_surgeries_treatmentcode}")
    """

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

    # Tray composition statistics for table 2
    composition = df.drop_duplicates(["net_definition", "article_definition"])
    n_unique_trays = composition["net_definition"].nunique()
    n_unique_instruments = composition["article_definition"].nunique()
 
    per_tray_counts = composition.groupby("net_definition").size()
    avg_instruments_per_tray = per_tray_counts.mean() if n_unique_trays else 0.0
    std_instruments_per_tray = per_tray_counts.std() if n_unique_trays else 0.0
    min_instruments_per_tray = per_tray_counts.min() if n_unique_trays else 0
    max_instruments_per_tray = per_tray_counts.max() if n_unique_trays else 0
    p95_instruments_per_tray = per_tray_counts.quantile(0.95) if n_unique_trays else 0.0

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
        "n_unique_instruments": n_unique_instruments,
        "avg_instruments_per_tray": avg_instruments_per_tray,
        "std_instruments_per_tray": std_instruments_per_tray,
        "min_instruments_per_tray": min_instruments_per_tray,
        "max_instruments_per_tray": max_instruments_per_tray,
        "p95_instruments_per_tray": p95_instruments_per_tray,
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
        f"\n"
        f"=== Table 2: Data Summary of Tray Composition ===\n"
        f"Number of unique trays used                                : {m['n_unique_trays']}\n"
        f"Number of unique instruments                                : {m['n_unique_instruments']}\n"
        f"Average number of instruments in each tray                  : {m['avg_instruments_per_tray']:.2f}\n"
        f"Standard deviation of the number of instruments per tray    : {m['std_instruments_per_tray']:.2f}\n"
        f"Minimum number of instruments in each tray                  : {m['min_instruments_per_tray']}\n"
        f"Maximum number of instruments in each tray                  : {m['max_instruments_per_tray']}\n"
        f"95th percentile of the number of instruments in each tray   : {m['p95_instruments_per_tray']:.0f}\n"
    )

def compute_table1_row_data(df: pd.DataFrame) -> pd.DataFrame:
    out = []
    for code, g in df.groupby("treatment_code", dropna=False):
        n_surg = g["surgery_id"].nunique()
        used_total = int(g["used"].sum())

        # overage (Deshpande): per (surgery, instrument) slot, supply - used, clipped >= 0
        supply = g.groupby(instrument_key).size().rename("supply")
        used   = g.groupby(instrument_key)["used"].sum().rename("used")
        pair   = pd.concat([supply, used], axis=1).fillna(0)
        surplus = pair["supply"] - pair["used"]
        overage_total = float(surplus.clip(lower=0).sum())

        out.append({
            "treatment_code": code,
            "surgeries": n_surg,
            "instruments_assigned_per_surgery": len(g) / n_surg if n_surg else 0.0,
            "instruments_used_per_surgery": used_total / n_surg if n_surg else 0.0,
            "overage_per_surgery": overage_total / n_surg if n_surg else 0.0,
        })
    return pd.DataFrame(out).set_index("treatment_code")


def build_table1(paths) -> pd.DataFrame:
    per_instance = [compute_table1_row_data(load_instance(p)) for p in paths]
    codes = per_instance[0].index

    surgeries = per_instance[0]["surgeries"]
    assigned  = per_instance[0]["instruments_assigned_per_surgery"]

    used_stack    = pd.concat([d["instruments_used_per_surgery"].rename(i)
                               for i, d in enumerate(per_instance)], axis=1)
    overage_stack = pd.concat([d["overage_per_surgery"].rename(i)
                               for i, d in enumerate(per_instance)], axis=1)

    table = pd.DataFrame({
        "Surgeries": surgeries,
        "Instruments assigned": assigned,
        "Instruments used (mean)": used_stack.mean(axis=1),
        "Instruments used (std)":  used_stack.std(axis=1),
        "Overage (mean)": overage_stack.mean(axis=1),
        "Overage (std)":  overage_stack.std(axis=1),
    }).sort_values("Surgeries", ascending=False)

    # Average row 
    avg = pd.Series({
        "Surgeries": np.nan,
        "Instruments assigned": table["Instruments assigned"].mean(),
        "Instruments used (mean)": table["Instruments used (mean)"].mean(),
        "Instruments used (std)": np.nan,
        "Overage (mean)": table["Overage (mean)"].mean(),
        "Overage (std)": np.nan,
    }, name="Average")

    return pd.concat([table, avg.to_frame().T])

if __name__ == "__main__":
    csv_path = "data/TraysKnown/simulatedData/instance00.csv"
    # Evaluate specific surgery type; choose "None" to evaluate all surgery types
    surgery_type = None       
    
    # Get results from one instance
    df = load_instance(csv_path)
    print(format_results(compute_metrics(df, surgery_type)))

    # Aggregate over all simulated instances and take mean / std across them
    paths = sorted(glob.glob("data/TraysKnown/simulatedData/instance*.csv"))
    print(aggregate_over_instances(paths, surgery_type))

    # Deshpande-style Table 1 by treatment_code
    print("\n=== Table 1: Surgeries, Instruments Assigned/Used and Overage by treatment_code ===")
    pd.set_option("display.float_format", lambda x: f"{x:.2f}")
    print(build_table1(paths).to_string(na_rep="—"))
    
    