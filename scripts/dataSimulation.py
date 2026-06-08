import pandas as pd
import numpy as np

##### Parameters #####
Cluster_proportions = [0.11, 0.54, 0.35]
Cluster_probs = [0.00, 0.1667, 0.45]

# What defines a surgery
Surgery_keys = ["room", "date", "planned_starttime", "treatment"]

##### Split instruments into clusters #####
def assign_clusters(n_instruments, rng):
    counts = np.round(np.array(Cluster_proportions) * n_instruments).astype(int)
    # Fix rounding -> every instrument assigned to exactly one cluster
    diff = n_instruments - counts.sum()
    counts[np.argmax(counts)] += diff
    counts = np.clip(counts, 0, None)
    # final safety: if clipping changed the total, patch cluster 1
    counts[1] += n_instruments - counts.sum()
 
    freq_labels = np.concatenate([np.full(c, k) for k, c in enumerate(counts)])
    rng.shuffle(freq_labels)
    return freq_labels

##### Simulate usage data per surgery #####
def simulate(net_content, usage, seed=420):
    # One instance 
    rng = np.random.default_rng(seed)
    
    # Assign a cluster to every instrument, per tray
    net_cont_copy = net_content.copy()
    cluster_map = {}

    for net_def, grp in net_cont_copy.groupby("net_definition"):
        instruments = grp["article_definition"].tolist()
        freq_labels = assign_clusters(len(instruments), rng)
        cluster_map[net_def] = dict(zip(instruments, freq_labels)) 

    net_cont_copy["cluster"] = [
        cluster_map[row.net_definition][row.article_definition]
        for row in net_cont_copy.itertuples()
    ]

    # Identify the individual surgeries and give them unique ids
    usage_copy = usage.copy()
    # Surgeries are identified by the keys specified above
    usage_copy["surgery_id"] = usage_copy.groupby(Surgery_keys, dropna=False).ngroup() + 1

    # Warning: tray content not available 
    missing = set(usage_copy["net_definition"]) - set(net_cont_copy["net_definition"])
    if missing:
        print(f"WARNING: {len(missing)} tray(s) in Usage have no instruments in Net content "
              f"and will be dropped: {sorted(missing)[:10]}")

    # Merge, every row one instrument 
    merged = usage_copy.merge(net_cont_copy, on="net_definition", how="inner")

    # Simulate usage probabilities using Bernoulli(p) with probabilities defined above
    p = merged["cluster"].map(dict(enumerate(Cluster_probs))).to_numpy()
    draws = rng.random(len(merged)) < p          # Bernoulli(p)
    tray_opened = merged["used_at_OR"].astype(int).to_numpy()
    merged["tray_opened"] = tray_opened

    # All instruments in unopened trays are not used
    merged["used"] = (tray_opened == 1) * draws.astype(int)  
 
    # Specify the columns you want to save 
    cols = ["net_definition", "net_name", "article_definition", "article_name",
            "cluster", "treatment", "treatment_code", "surgery_id", "tray_opened", "used"]
    cols = [c for c in cols if c in merged.columns]
    return merged[cols].sort_values(["surgery_id", "net_definition", "article_definition"]).reset_index(drop=True)

##### Load data and run #####
def run(excel_path, n_instances=1, base_seed=0,
        usage_sheet="Usage", net_sheet="Net content"):
    
    # Read excel files
    usage = pd.read_excel(excel_path, sheet_name=usage_sheet, dtype=str)
    net_content = pd.read_excel(excel_path, sheet_name=net_sheet, dtype=str)

    usage["used_at_OR"] = pd.to_numeric(usage["used_at_OR"], errors="coerce").fillna(0).astype(int)

    if n_instances == 1:
        return simulate(net_content, usage, seed=base_seed)
    return {i: simulate(net_content, usage, seed=base_seed + i) for i in range(n_instances)}


##### Main #####
if __name__ == "__main__":
    outdir = "data/simulatedData"   

    instances = run("data/historicData/synthetic_data.xlsx",
                    n_instances=10, base_seed=420)

    # Save all instances 
    for i, df in instances.items():
        path = f"{outdir}/simulated_usage_seed420_instance{i:02d}.csv"
        df.to_csv(path, index=False)
        print(f"saved {path}  |  rows: {len(df)}  |  surgeries: {df.surgery_id.nunique()}  |  "
              f"utilisation rate: {df.loc[df.tray_opened==1, 'used'].mean():.3f}")
        
        util_rate = df.loc[df.tray_opened == 1, 'used'].mean()
        if util_rate < 0.20 or util_rate > 0.30:
            print("WARNING: Utilisation rate does not match Deshpande.")
