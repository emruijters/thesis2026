import pandas as pd
import numpy as np

##### Parameters #####

# Proposal: 0.11, 0.54, 0.35 ; 0.00, 0.1667, 0.45, 1.00
# Maastricht (own calculations): 0.14, 0.41, 0.45 ; 0.00, 0.085, 0.490, 1.00
Cluster_proportions = [0.14, 0.41, 0.45]
Cluster_probs = [0.00, 0.085, 0.490, 1.00]

# What defines a surgery
Surgery_keys = ["room", "date", "planned_starttime_rounded", "treatment"]

##### Split instruments into clusters #####
def assign_clusters(n_instruments, rng):
    # draw each instrument's cluster independently; proportions hold in expectation
    return rng.choice(len(Cluster_proportions), size=n_instruments, p=Cluster_proportions)

##### Deterministic part - runs only once #####
def build(net_content, usage):
    net_cont_copy = net_content.copy()
    net_cont_copy = net_cont_copy.dropna(subset=["net_definition"])

    # Number the surgeries
    usage_copy = usage.copy()

    t = pd.to_datetime(usage_copy["planned_starttime"], format="%H:%M:%S", errors="coerce")
    usage_copy = usage_copy.assign(_floored = t.dt.floor("26min"))

    # find the groups where flooring collapses >1 distinct exact time
    g = (usage_copy
        .groupby(["room", "date", "_floored", "treatment"], dropna=False)
        .agg(n_distinct_times = ("planned_starttime", "nunique"),
            times = ("planned_starttime", lambda s: sorted(s.dropna().unique())))
        .reset_index())

    collapsed = g[g["n_distinct_times"] > 1]
    for _, row in collapsed.iterrows():
        print(f"{row['room']} | {row['date']} | {row['treatment']}")
        print(f"   times merged into one bin: {row['times']}")
        print()

    # Round the planned_starttime (17:56 and 17:58 are the same surgery)
    usage_copy["planned_starttime_rounded"] = pd.to_datetime(
        usage_copy["planned_starttime"], format="%H:%M:%S", errors="coerce"
    ).dt.floor("26min")            

    # Surgeries are identified by the keys specified above
    usage_copy["surgery_id"] = usage_copy.groupby(Surgery_keys, dropna=False).ngroup() + 1

    # If treatment_code is empty, but another row has the same treatment with a treatment_code, use this one
    usage_copy["treatment_code"] = usage_copy["treatment_code"].replace("", np.nan)
    usage_copy["treatment_code"] = (
        usage_copy.groupby("surgery_id")["treatment_code"]
        .transform(lambda s: s.ffill().bfill())
    )
    
    # OPTIONAL : drop all surgeries without a treatment_code 
    usage_copy = usage_copy[usage_copy["treatment_code"].notna()]

    # Warning: tray content not available 
    missing_nets = set(usage_copy["net_definition"]) - set(net_cont_copy["net_definition"])
    if missing_nets:
        print(f"WARNING: {len(missing_nets)} tray(s) in Usage have no instruments in Net content: {sorted(missing_nets)[:10]}")

    # Check if an unknown net is a single known instrument; remove surgeries that have an unknown tray assigned
    single_instr_rows = []
    matched = []
    bad_nets = []      

    for net_def in missing_nets:
        usage_rows = usage_copy.loc[usage_copy["net_definition"] == net_def]
        instrument_name = usage_rows["net_name"].iloc[0]   # assume net_name identifies the instrument
        matches = net_cont_copy.loc[net_cont_copy["article_name"] == instrument_name]

        if len(matches) > 0:
            # matched to an existing article -> keep as a synthetic single-instrument tray
            m = matches.iloc[0]
            single_instr_rows.append({
                "net_definition": net_def,
                "article_definition": m["article_definition"],
                "article_name": m["article_name"]
            })
            matched.append(net_def)
        else:
            # cannot match (unknown net or unknown single instrument) -> mark for removal
            bad_nets.append(net_def)

    # only matched trays become synthetic content; single-instrument trays are the matched ones
    single_tray_ids = set(matched)
    if single_instr_rows:
        net_cont_copy = pd.concat([net_cont_copy, pd.DataFrame(single_instr_rows)],
                                  ignore_index=True)
    if matched:
        print(f"WARNING: {len(matched)} tray(s) identified as single-instrument trays and matched to an existing article in Net content.")

    # Remove entire surgeries that contain any unmatchable tray, so only surgeries
    # whose trays all have known content remain.
    surgeries_to_drop = set(
        usage_copy.loc[usage_copy["net_definition"].isin(bad_nets), "surgery_id"]
    )
    if bad_nets:
        n_before = usage_copy["surgery_id"].nunique()
        usage_copy = usage_copy.loc[~usage_copy["surgery_id"].isin(surgeries_to_drop)].copy()
        n_after = usage_copy["surgery_id"].nunique()
        print(f"WARNING: removed {len(surgeries_to_drop)} surgery(ies) containing "
              f"{len(bad_nets)} unmatchable tray(s); {n_before} -> {n_after} surgeries remain.")

    # The merge skeleton: one row per (instrument, surgery)
    merged = usage_copy.merge(net_cont_copy, on="net_definition", how="inner")
    merged["tray_opened"] = merged["used_at_OR"].astype(int)
    
    # Fixes cluster = 3 for single instrument clusters
    merged["fixed_cluster"] = np.where(
        merged["net_definition"].isin(single_tray_ids), 3, np.nan)
    
    return merged

##### Simulate usage data for every instance #####
def simulate_instance(skeleton, seed=420):
    # One instance
    rng = np.random.default_rng(seed)
    df = skeleton.reset_index(drop=True).copy()

    # Assign clusters ONCE per article_definition (one cluster per instrument),
    # re-randomised each instance. Single-instrument trays keep their fixed cluster 3.
    is_normal = df["fixed_cluster"].isna().to_numpy()
    normal_articles = df.loc[is_normal, "article_definition"].unique()
    drawn = assign_clusters(len(normal_articles), rng)
    article_cluster = dict(zip(normal_articles, drawn))     # {instrument: cluster}

    cluster = np.array(df["fixed_cluster"].to_numpy(dtype=float), copy=True)  # 3 or NaN
    cluster[is_normal] = df.loc[is_normal, "article_definition"].map(article_cluster).to_numpy()
    df["cluster"] = cluster.astype(int)

    # Simulate usage probabilities using Bernoulli(p) with probabilities defined above
    p = df["cluster"].map(dict(enumerate(Cluster_probs))).to_numpy()
    draws = rng.random(len(df)) < p          # Bernoulli(p)
    df["used"] = (df["tray_opened"].to_numpy() == 1) * draws.astype(int)

    # Repair: if an opened tray has no used instruments, set one instrument to used = 1 (higher prob in higher cluster)
    opened = df[df["tray_opened"] == 1]
    for (sid, net_def), grp in opened.groupby(["surgery_id", "net_definition"]):
        if grp["used"].sum() == 0:
            p_grp = grp["cluster"].map(dict(enumerate(Cluster_probs))).to_numpy()
            # If all instruments are in cluster 0, argmax fallback 
            if p_grp.sum() == 0:                      
                chosen = grp["cluster"].idxmax()
            else:
                chosen = rng.choice(grp.index, p=p_grp / p_grp.sum())
            df.loc[chosen, "used"] = 1

    cols = ["net_definition", "net_name", "article_definition", "article_name",
            "cluster", "treatment", "treatment_code", "surgery_id", "tray_opened", "used"]
    cols = [c for c in cols if c in df.columns]
    return df[cols].sort_values(["surgery_id", "net_definition", "article_definition"]).reset_index(drop=True)

##### Load data and run #####
def run(excel_path, n_instances=1, base_seed=0,
        usage_sheet="Usage", net_sheet="Net content"):
    
    # Read excel files
    usage = pd.read_excel(excel_path, sheet_name=usage_sheet, dtype=str)
    net_content = pd.read_excel(excel_path, sheet_name=net_sheet, dtype=str)

    usage["used_at_OR"] = pd.to_numeric(usage["used_at_OR"], errors="coerce").fillna(0).astype(int)

    # Build skeleton once
    skeleton = build(net_content, usage)

    # Simulate instance(s)
    if n_instances == 1:
        return simulate_instance(skeleton, seed=base_seed)
    return {i: simulate_instance(skeleton, seed=base_seed + i) for i in range(n_instances)}


##### Main #####
if __name__ == "__main__":
    outdir = "data/TraysKnown/simulatedData"   

    instances = run("data/historicData/EMC_data.xlsx",
                    n_instances=10, base_seed=420)

    # If only one instance 
    if isinstance(instances, pd.DataFrame):
        instances = {0: instances}
    
    # Save all instances 
    n_instances = 10
    sum_usage = 0
    for i, df in instances.items():
        path = f"{outdir}/instance{i:02d}.csv"
        df.to_csv(path, index=False)
        print(f"saved {path}  |  rows: {len(df)}  |  surgeries: {df.surgery_id.nunique()}  |  "
              f"utilisation rate: {df["used"].mean():.3f}")
        # util_rate_opened = df.loc[df.tray_opened == 1, 'used'].mean()
        # print(util_rate_opened)

        util_rate = df["used"].mean()
        if util_rate < 0.20 or util_rate > 0.30:
            print("WARNING: Utilisation rate does not match Deshpande.")
        sum_usage = sum_usage + util_rate

    average_usage = sum_usage / n_instances
    print(f"Average usage rate: {average_usage:.3f}")
