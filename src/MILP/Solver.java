package MILP;

import java.io.File;
import java.util.List;
import java.util.Set;

import com.gurobi.gurobi.*;

import core.*;

public class Solver {
    private TrayInstance instance;
    private final boolean phase0;
    private final double beta1;
    private final double beta2;
    private final double lambda1;
    private final double lambda2;
    private final int T;
    private final int C;
    private final double cutoff;
    
    public Solver(TrayInstance instance, boolean phase0, double beta1, double beta2, double lambda1, double lambda2, int T, int C, double cutoff) {
        this.instance = instance;
        this.phase0 = phase0;
        this.beta1 = beta1;
        this.beta2 = beta2;
        this.lambda1 = lambda1;
        this.lambda2 = lambda2;
        this.T = T;
        this.C = C;
        this.cutoff = cutoff;
    }

    // Long key to handle heap space error 
    private static long key(int m, int i) {
        return (((long) m) << 32) | (i & 0xffffffffL);
    }

    public double[] run() {
        // The full run loop: first create gurobi environment
        GRBEnv env = null;
        try {
            env = new GRBEnv(true);
            env.set(GRB.IntParam.OutputFlag, 0);
            env.start();

            if (phase0) {
                System.out.println("Running phase 0 (single-instrument tray consolidation):");
                long startP0 = System.currentTimeMillis();
                this.instance = applyPhase0(this.instance);
                long endP0 = System.currentTimeMillis();
                System.out.println("Phase 0 finished. Runtime: " + ((endP0 - startP0) / 1000.0) + " s.\n");
            }

            final int nSurgeries = instance.getNSurgeries();

            // Solve tray rationalisation 
            System.out.println("Running tray rationalisation step:");
            long startTimeTR = System.currentTimeMillis();
            TrayRationalisationResult trr = solveTrayRationalisation(env);
            long endTimeTR = System.currentTimeMillis();
            System.out.println("Tray rationalisation step finished.");
            System.out.println("Runtime tray rationalisation step: " + ((endTimeTR - startTimeTR) / 1000) + " s.");
            
            double overagePerSurgery = trr.totalOverage / nSurgeries;
            double underagePerSurgery = trr.totalUnderage / nSurgeries;
            double openedTraysPerSurgery = trr.totalOpenedTrays / (double) nSurgeries;
            
            System.out.printf("overage=%.2f  underage=%.2f  openedTrays=%.2f", overagePerSurgery, underagePerSurgery, openedTraysPerSurgery);

            // Solve tray creation 
            System.out.println("");
            System.out.println("Running add-on tray step:");
            long startTimeAOT = System.currentTimeMillis();
            AddOnTrayResult aotr = solveAddOnTrayStep(env, trr);
            long endTimeAOT = System.currentTimeMillis();
            System.out.println("Add-on tray step finished.");
            System.out.println("Runtime add-on tray step: " + ((endTimeAOT - startTimeAOT) / 1000) + " s.");

            // Print metrics 
            java.util.Set<Integer> Ihat = new java.util.HashSet<>(trr.Ihat);

            // Overage: stage-1 overage for i∉Î (unchanged) + stage-2 overage for i∈Î (updated).
            double overageOutsideIhat = trr.overageExcludingInstruments(Ihat);
            double totalOverage = overageOutsideIhat + aotr.totalOverage;

            // Underage: after stage 1 only Î instruments have positive underage; stage 2 reports their final values.
            double totalUnderage = aotr.totalUnderage;

            // Opened trays: disjoint sets T0 (stage 1) and T1 (stage 2), so they add directly.
            int totalOpenedTrays = trr.totalOpenedTrays + aotr.totalOpenedTrays;

            // Number of new trays created
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (long k : aotr.xStar.keySet()) {
                used.add((int) (k >>> 32));   // high 32 bits = tray index
            }
            int newTraysCreated = used.size();

            // Print all metrics 
            System.out.println("\nFinal results:");
            System.out.printf("Overage per surgery      : %.2f%n", totalOverage / nSurgeries);
            System.out.printf("Underage per surgery     : %.2f%n", totalUnderage / nSurgeries);
            System.out.printf("Opened trays per surgery : %.2f%n", totalOpenedTrays / (double) nSurgeries);
            System.out.printf("  - existing (T0)        : %.2f%n", trr.totalOpenedTrays / (double) nSurgeries);
            System.out.printf("  - add-on   (T1)        : %.2f%n", aotr.totalOpenedTrays / (double) nSurgeries);
            System.out.println("New add-on trays created: " + newTraysCreated + " (of " + this.T + " permitted)");

            // Print statistics of add-on trays
            printAddOnTrayStats(aotr);

            // Check metrics again 
            double[] finalResults = printPhysicalMetrics(instance, trr, aotr);

            return finalResults;

            // write results as a csv file 
            // writeResultsCsv(instance, trr, aotr, new File("data/TreatmentCode/results/decomposition/instance00_decomp.csv"));

        } catch (GRBException e) {
        // Infeasible, no-solution, or any Gurobi error for THIS parameter set:
        // report and return so the parameter sweep continues.
        System.out.println("  -> Skipped this parameter set: " + e.getMessage());
        double[] result = {0.0, 0.0, 0.0};
        return result;
        } finally {
            if (env != null) {
                try {
                    env.dispose();
                } catch (GRBException e) {
                    System.out.println("  -> Error disposing environment: " + e.getMessage());
                }
            }
        }
    }

    private TrayInstance applyPhase0(TrayInstance in) {
        final int K = in.getNInstruments();
        final List<Tray> oldTrays = in.getTrays();
        final List<Surgery> surgeries = in.getSurgeries();
        final int nTrays = oldTrays.size();

        // 1. Classify trays. A single-instrument tray has exactly one instrument TYPE
        //    present. (To require exactly one physical copy instead, test t.nCopies == 1.)
        int[] soleInstrument = new int[nTrays];
        boolean[] isSingle   = new boolean[nTrays];
        for (Tray t : oldTrays) {
            int distinct = 0, last = -1;
            for (int i = 0; i < K; i++) {
                if (t.composition[i] > 0) { distinct++; last = i; }
            }
            isSingle[t.index]       = (distinct == 1);
            soleInstrument[t.index] = (distinct == 1) ? last : -1;
        }

        // 2. Per surgery: which assigned multi-trays are opened under the ORIGINAL
        //    composition (supply >= 1 instrument used in that surgery).
        java.util.Map<Integer, List<Integer>> openedMultiOfSurgery = new java.util.HashMap<>();
        for (Surgery s : surgeries) {
            List<Integer> opened = new java.util.ArrayList<>();
            for (int t : s.existingTrays) {
                if (isSingle[t]) continue;
                int[] comp = oldTrays.get(t).composition;
                for (int i : s.usedInstruments) {
                    if (comp[i] > 0) { opened.add(t); break; }
                }
            }
            openedMultiOfSurgery.put(s.index, opened);
        }

        // 3. Single instruments and the surgeries that use them.
        java.util.Set<Integer> singleInstruments = new java.util.TreeSet<>();
        for (Tray t : oldTrays) {
            if (isSingle[t.index]) singleInstruments.add(soleInstrument[t.index]);
        }
        java.util.Map<Integer, List<Integer>> surgeriesUsing = new java.util.HashMap<>();
        for (int i : singleInstruments) surgeriesUsing.put(i, new java.util.ArrayList<>());
        for (Surgery s : surgeries) {
            for (int i : s.usedInstruments) {
                if (singleInstruments.contains(i)) surgeriesUsing.get(i).add(s.index);
            }
        }

        // 4. Coverability. (An instrument never used anywhere is coverable trivially:
        //    its single tray is pure waste and is simply dropped.)
        java.util.Set<Integer> mergedInstruments = new java.util.HashSet<>();
        for (int i : singleInstruments) {
            boolean coverable = true;
            for (int m : surgeriesUsing.get(i)) {
                if (openedMultiOfSurgery.get(m).isEmpty()) { coverable = false; break; }
            }
            if (coverable) mergedInstruments.add(i);
        }

        // 5. New compositions: seed each merged instrument into every already-opened
        //    multi-tray in each surgery that uses it. Seed value 1 just creates the
        //    x variable; rationalisation (x in [0, INF)) sets the actual copy count.
        int[][] newComp = new int[nTrays][];
        for (Tray t : oldTrays) newComp[t.index] = t.composition.clone();
        for (int i : mergedInstruments) {
            for (int m : surgeriesUsing.get(i)) {
                for (int t : openedMultiOfSurgery.get(m)) {
                    if (newComp[t][i] == 0) newComp[t][i] = 1;
                }
            }
        }

        // 6. Remove single trays whose instrument was merged.
        boolean[] remove = new boolean[nTrays];
        for (Tray t : oldTrays) {
            if (isSingle[t.index] && mergedInstruments.contains(soleInstrument[t.index])) {
                remove[t.index] = true;
            }
        }

        // Reporting: sterilisation cycles eliminated = openings of the removed single trays.
        int openingsEliminated = 0;
        for (Surgery s : surgeries) {
            for (int t : s.existingTrays) {
                if (remove[t] && in.getUsage(s.index, soleInstrument[t]) > 0) openingsEliminated++;
            }
        }
        int nSingle = 0, nKept = 0;
        for (Tray t : oldTrays) if (isSingle[t.index]) { nSingle++; if (!remove[t.index]) nKept++; }
        System.out.println("  single-instrument trays found      : " + nSingle);
        System.out.println("  merged (removed)                   : " + (nSingle - nKept));
        System.out.println("  kept (uncoverable)                 : " + nKept);
        System.out.println("  instruments merged into multi-trays: " + mergedInstruments.size());
        System.out.println("  trays: " + nTrays + " -> " + (nTrays - (nSingle - nKept)));
        System.out.println("  single-tray openings eliminated    : " + openingsEliminated
                + " (" + String.format("%.2f", openingsEliminated / (double) in.getNSurgeries())
                + " per surgery)");

        // 7. Reindex surviving trays (dense 0..).
        int[] oldToNew = new int[nTrays];
        java.util.Arrays.fill(oldToNew, -1);
        List<Tray> newTrays = new java.util.ArrayList<>();
        int newIdx = 0;
        for (Tray t : oldTrays) {
            if (remove[t.index]) continue;
            oldToNew[t.index] = newIdx;
            newTrays.add(new Tray(newIdx, t.netID, t.netName, newComp[t.index]));
            newIdx++;
        }

        // 8. Rebuild surgeries with remapped tray indices (surgery indices unchanged).
        List<Surgery> newSurgeries = new java.util.ArrayList<>();
        for (Surgery s : surgeries) {
            newSurgeries.add(new Surgery(
                    s.index, s.surgeryID, s.surgeryTypeIndex,
                    remap(s.existingTrays, oldToNew),
                    remap(s.openedTrays,   oldToNew),
                    remap(s.closedTrays,   oldToNew),
                    s.usedInstruments));
        }

        // 9. Carry over the unchanged pieces (surgery indices, instruments, usage, types).
        final int P = in.getNSurgeryTypes();
        List<List<Integer>> sot = new java.util.ArrayList<>();
        for (int p = 0; p < P; p++) sot.add(new java.util.ArrayList<>(in.getSurgeriesOfType(p)));

        String[] codes  = new String[P];
        String[] treats = new String[P];
        for (int p = 0; p < P; p++) { codes[p] = in.getSurgeryTypeCode(p); treats[p] = in.getSurgeryTypeTreatment(p); }

        int[][] usage = new int[in.getNSurgeries()][K];
        for (int m = 0; m < in.getNSurgeries(); m++)
            for (int i = 0; i < K; i++)
                usage[m][i] = in.getUsage(m, i);

        List<Instrument> instruments = new java.util.ArrayList<>(in.getInstruments());

        return new TrayInstance(newSurgeries, newTrays, instruments, usage, sot, P, codes, treats);
    }

    private static List<Integer> remap(List<Integer> old, int[] oldToNew) {
        List<Integer> out = new java.util.ArrayList<>();
        for (int t : old) if (oldToNew[t] >= 0) out.add(oldToNew[t]);
        return out;
    }

    public TrayRationalisationResult solveTrayRationalisation(GRBEnv env) throws GRBException {
        final int N = instance.getNSurgeries();
        final int K = instance.getNInstruments();
        final int P = instance.getNSurgeryTypes();
        final List<Surgery> surgeries = instance.getSurgeries();
        final List<Tray>    trays     = instance.getTrays();

        GRBModel model = new GRBModel(env);
        model.set(GRB.IntAttr.ModelSense, GRB.MINIMIZE);
        model.set(GRB.DoubleParam.TimeLimit, 900.0);

        // Decision variables ; stored in map, not N x K array

        // x_{t,i} only for i in I(t) -> keyed by (t,i)
        java.util.Map<Long, GRBVar> x = new java.util.HashMap<>();
        for (Tray t : trays) {
            int ti = t.index;
            int[] comp = t.composition;
            for (int i = 0; i < K; i++) {
                if (comp[i] > 0) {
                    x.put(key(ti, i),
                          model.addVar(0.0, GRB.INFINITY, 0.0, GRB.INTEGER, "x[" + ti + "," + i + "]"));
                }
            }
        }

        // v & w, only for instruments relevant to surgery m, keyed by (m,i)
        java.util.Map<Long, GRBVar> v = new java.util.HashMap<>();
        java.util.Map<Long, GRBVar> w = new java.util.HashMap<>();
        for (Surgery s : surgeries) {
            int m = s.index;
 
            java.util.Set<Integer> relevant = new java.util.HashSet<>(s.usedInstruments);
            for (int ti : s.existingTrays) {
                int[] comp = trays.get(ti).composition;
                for (int i = 0; i < K; i++) {
                    if (comp[i] > 0) relevant.add(i);
                }
            }
 
            for (int i : relevant) {
                long k = key(m, i);
                v.put(k, model.addVar(0.0, GRB.INFINITY, 0.0, GRB.CONTINUOUS, "v[" + m + "," + i + "]"));
                w.put(k, model.addVar(0.0, GRB.INFINITY, 0.0, GRB.CONTINUOUS, "w[" + m + "," + i + "]"));
            }
        }

        // b_{m,t} only for t in T0(m), keyed by (m,t)
        java.util.Map<Long, GRBVar> b = new java.util.HashMap<>();
        for (Surgery s : surgeries) {
            int m = s.index;
            for (int ti : s.existingTrays) {
                b.put(key(m, ti),
                      model.addVar(0.0, 1.0, 0.0, GRB.BINARY, "b[" + m + "," + ti + "]"));
            }
        }
 
        model.update();

        // Objective 
        GRBLinExpr obj = new GRBLinExpr();
        // Total Overage
        for (GRBVar vmi : v.values()) {
            obj.addTerm(1.0, vmi);                       
        }
        // Opened trays
        for (GRBVar bmt : b.values()) {
            obj.addTerm(lambda1, bmt);                   
        }
        model.setObjective(obj, GRB.MINIMIZE);

        // First two constraints: overage and underage definition 
        for (Surgery s : surgeries) {
            int m = s.index;
            List<Integer> t0 = s.existingTrays;  
            // Iterate only over (m,i) pairs that exist 
            for (int i = 0; i < K; i++) {
                long k = key(m, i);
                GRBVar vmi = v.get(k);
                if (vmi == null) continue;               
                GRBVar wmi = w.get(k);
 
                GRBLinExpr supply = new GRBLinExpr();
                for (int ti : t0) {
                    GRBVar xti = x.get(key(ti, i));
                    if (xti != null) supply.addTerm(1.0, xti);
                }
                int u = instance.getUsage(m, i);
 
                // v_{m,i} >= supply - u   ==>   v - supply >= -u
                GRBLinExpr constraint1 = new GRBLinExpr();
                constraint1.addTerm(1.0, vmi);
                constraint1.multAdd(-1.0, supply);
                model.addConstr(constraint1, GRB.GREATER_EQUAL, -u, "ov[" + m + "," + i + "]");
 
                // w_{m,i} >= u - supply   ==>   w + supply >= u
                GRBLinExpr constraint2 = new GRBLinExpr();
                constraint2.addTerm(1.0, wmi);
                constraint2.add(supply);
                model.addConstr(constraint2, GRB.GREATER_EQUAL, u, "un[" + m + "," + i + "]");
            }
        }

        // Third constraint: service level allowed, beta1 controls how aggressively rationalisation may under-supply

        // Easy method using average 1.21 
        /*
        for (int p = 0; p < P; p++) {           
            GRBLinExpr lhs = new GRBLinExpr();
            for (int m : instance.getSurgeriesOfType(p)) {
                for (int i = 0; i < K; i++) {
                    GRBVar wmi = w.get(key(m, i));
                    if (wmi != null) lhs.addTerm(1.0, wmi);
                } 
            }

            double Wmp = Wm * instance.getSurgeriesOfType(p).size();
            model.addConstr(lhs, GRB.LESS_EQUAL, (1 - beta1) * Wmp, "serv[" + p + "]");
        }
         */
        
        // Difficult move with different Wmax_p per surgery type 
        Set<String> flagged = instance.computeFlaggedArticles(cutoff);
        double[] Wmax = instance.computeUnderagePerType(flagged);

        for (int p = 0; p < P; p++) {
            GRBLinExpr lhs = new GRBLinExpr();
            for (int m : instance.getSurgeriesOfType(p)) {
                for (int i = 0; i < K; i++) {
                    GRBVar wmi = w.get(key(m, i));
                    if (wmi != null) lhs.addTerm(1.0, wmi);
                }
            }

            model.addConstr(lhs, GRB.LESS_EQUAL, (1 - beta1) * Wmax[p], "serv[" + p + "]");
        }

        // New third constraint: use tray reduction as threshold, they have 1.21 underage per surgery 
        // Global variant: single constraint, total underage <= 1.21 * N
        /*
        double underagePerSurgery = 1.21;
        GRBLinExpr lhs = new GRBLinExpr();
        for (GRBVar wmi : w.values()) lhs.addTerm(1.0, wmi);
        model.addConstr(lhs, GRB.LESS_EQUAL, underagePerSurgery * instance.getNSurgeries(), "serv_global");
         */

        // Fourth and fifth constraints: opened tray definition 
        for (Surgery s : surgeries) {
            int m = s.index;
            for (int ti : s.existingTrays) {
                Tray t = trays.get(ti);
                GRBVar bmt = b.get(key(m, ti));

                GRBLinExpr usedSupply = new GRBLinExpr();
                for (int i = 0; i < K; i++) {
                    if (instance.isUsed(m, i)) {             
                        GRBVar xti = x.get(key(ti, i));
                        if (xti != null) usedSupply.addTerm(1.0, xti);
                    }
                }
 
                // b_{m,t} <= usedSupply   ==>   b - usedSupply <= 0
                GRBLinExpr constraint4 = new GRBLinExpr();
                constraint4.addTerm(1.0, bmt);
                constraint4.multAdd(-1.0, usedSupply);
                model.addConstr(constraint4, GRB.LESS_EQUAL, 0.0, "open_lo[" + m + "," + ti + "]");
 
                // n_t * b_{m,t} >= usedSupply   ==>   n_t*b - usedSupply >= 0
                int nt = t.nCopies;
                GRBLinExpr constraint5 = new GRBLinExpr();
                constraint5.addTerm(nt, bmt);
                constraint5.multAdd(-1.0, usedSupply);
                model.addConstr(constraint5, GRB.GREATER_EQUAL, 0.0, "open_hi[" + m + "," + ti + "]");
            }
        }

        // Do the optimisation 
        model.optimize();

        int status = model.get(GRB.IntAttr.Status);
        if (status == GRB.INFEASIBLE || status == GRB.INF_OR_UNBD) {
            model.dispose();
            throw new GRBException("infeasible");   // caught in run(), see below
        }
        if (model.get(GRB.IntAttr.SolCount) == 0) {
            // Time limit hit with no feasible solution found, or other no-solution status
            model.dispose();
            throw new GRBException("no feasible solution within time limit");
        }

        // Extract solution (store nonzero only)
        TrayRationalisationResult res = new TrayRationalisationResult(N, K, P, trays.size());
        
        // x̂_{t,i}
        for (Tray t : trays) {
            int ti = t.index;
            for (int i = 0; i < K; i++) {
                GRBVar xti = x.get(key(ti, i));
                if (xti != null) {
                    int val = (int) Math.round(xti.get(GRB.DoubleAttr.X));
                    if (val != 0) res.xHat.put(TrayRationalisationResult.key(ti, i), val);
                }
            }
        }

        // v̂, ŵ, opened trays, Ihat, W1_p
        boolean[] underageInstr = new boolean[K];

        for (Surgery s : surgeries) {
            int m = s.index;
            int p = s.surgeryTypeIndex;

            for (int i = 0; i < K; i++) {
                long k = key(m, i);
                GRBVar vmi = v.get(k);
                if (vmi == null) continue;
                GRBVar wmi = w.get(k);
 
                // Get overage and underage 
                double vv = vmi.get(GRB.DoubleAttr.X);
                double ww = wmi.get(GRB.DoubleAttr.X);
                
                res.totalOverage  += vv;
                res.totalUnderage += ww;

                long rk = TrayRationalisationResult.key(m, i);

                if (vv > 1e-9) {
                    res.vHat.put(rk, vv);
                }

                if (ww > 1e-6) {
                    res.wHat.put(rk,ww);

                    // Instrument has supplied underage 
                    underageInstr[i] = true; 
                    
                    // Sum of all underage 
                    res.W1[p] += ww;            
                }
            }
        }

        // Get Ihat
        for (int i = 0; i < K; i++) {
            if (underageInstr[i]) {
                res.Ihat.add(i);
            } 
        } 
 
        // Opened trays 
        for (Surgery s : surgeries) {
            int m = s.index;
            for (int ti : s.existingTrays) {
                if (b.get(key(m, ti)).get(GRB.DoubleAttr.X) > 0.5) {
                    res.bHat.put(TrayRationalisationResult.key(m, ti), Boolean.TRUE);
                    res.totalOpenedTrays++;
                }
            }
        }
 
        res.objective = model.get(GRB.DoubleAttr.ObjVal);
        model.dispose();
        
        return res;
    }

    public AddOnTrayResult solveAddOnTrayStep(GRBEnv env, TrayRationalisationResult trr) throws GRBException {
        final int N = instance.getNSurgeries();
        final int K = instance.getNInstruments();
        final int P = instance.getNSurgeryTypes();
        final List<Surgery> surgeries = instance.getSurgeries();
        final List<Tray>    trays     = instance.getTrays();

        final List<Integer> Ihat = trr.Ihat;
        final int Tnew = this.T;
        final double M = this.C;

        // If Ihat is empty, no add-on trays needed
        if (Ihat.isEmpty()) {
            return new AddOnTrayResult(N, K, P, trays.size());
        }

        GRBModel model = new GRBModel(env);
        model.set(GRB.IntAttr.ModelSense, GRB.MINIMIZE);  
        // Time limit of 15 minutes
        model.set(GRB.DoubleParam.TimeLimit, 900.0);

        // MIP gap of 0.01%
        model.set(GRB.DoubleParam.MIPGap, 0.01);


        // Instruments that are actually usable by SOME surgery type (union of relevantByType).
        // 
        java.util.Map<Integer, java.util.Set<Integer>> relevantByType = new java.util.HashMap<>();
        for (int p = 0; p < P; p++) {
            relevantByType.put(p, new java.util.HashSet<>());
        }

        
        java.util.Set<Integer> IhatSet = new java.util.HashSet<>(Ihat);
        for (Surgery s : surgeries) {
            int p = s.surgeryTypeIndex;
            java.util.Set<Integer> rel = relevantByType.get(p);
            for (int i : s.usedInstruments) {   // only instruments actually USED by this surgery
                if (IhatSet.contains(i)) rel.add(i);
            }
        }

        // For tractability 
        // suppliable: instruments any add-on tray could hold (union of per-type relevant sets)
        java.util.Set<Integer> suppliable = new java.util.HashSet<>();
        for (int p = 0; p < P; p++) suppliable.addAll(relevantByType.get(p));

        // underageTypes: types that actually have underage after stage 1 (only these need add-on coverage)
        java.util.Set<Integer> underageTypes = new java.util.HashSet<>();
        for (Surgery s : surgeries) {
            int p = s.surgeryTypeIndex;
            for (int i : s.usedInstruments) {
                if (IhatSet.contains(i)) { 
                    underageTypes.add(p); 
                    break; 
                }
            }
        }

        // Decision variables 
        //x*_{t,i}, only for i in Ihat
        java.util.Map<Long, GRBVar> x = new java.util.HashMap<>();
        for (int t = 0; t < Tnew; t++) {
            for (int i : suppliable) {
                x.put(key(t,i), model.addVar(0.0, C, 0.0, GRB.INTEGER, "x[" + t + "," + i + "]"));
            }
        }

        // a_{t,p}, if tray t is assigned to surgery type p
        java.util.Map<Long, GRBVar> a = new java.util.HashMap<>();
        for (int t = 0; t < Tnew; t++) {
            for (int p : underageTypes) {
                a.put(key(t, p), model.addVar(0.0, 1.0, 0.0, GRB.BINARY, null));
            }
        }
        // phi_{t,p,i}: Deshpande's auxiliary variable for linearisation, only for relevant (p,i) pairs (when some surgery of type p uses/holds instrument i)

        // Create phi only for relevant pairs 
        java.util.Map<Long, GRBVar> phi = new java.util.HashMap<>();
        for (int t = 0; t < Tnew; t++) {
            for (int p : underageTypes) {
                for (int i : suppliable) {          // WIDENED: suppliable, not relevantByType(p)
                    phi.put(key3(t, p, i), model.addVar(0.0, GRB.INFINITY, 0.0, GRB.CONTINUOUS, null));
                }
            }
        }

        // v & w , only i in Ihat 
        java.util.Map<Long, GRBVar> v = new java.util.HashMap<>();
        java.util.Map<Long, GRBVar> w = new java.util.HashMap<>();
        for (Surgery s : surgeries) {
            int m = s.index;

            // instruments that could appear in m's trays:
            java.util.Set<Integer> relevant = new java.util.HashSet<>(s.usedInstruments);
            // (a) instruments physically in m's existing trays (via fixed x̂ — same as stage 1)
            for (int ti : s.existingTrays) {
                for (int i = 0; i < K; i++) {
                    if (trr.getXHat(ti, i) > 0) relevant.add(i);
                }
            }
            relevant.addAll(suppliable);

            for (int i : relevant) {
                v.put(key(m, i), model.addVar(0.0, GRB.INFINITY, 0.0, GRB.CONTINUOUS, null));
            }
            // underage: only instruments m uses (can only be short of what you need), ∩ Î
            for (int i : s.usedInstruments) {
                if (IhatSet.contains(i)) {
                    w.put(key(m, i), model.addVar(0.0, GRB.INFINITY, 0.0, GRB.CONTINUOUS, null));
                }
            }
        }

        // b_{m,t}: if new tray t is opened during surgery m, only for relevant b 
        java.util.Map<Long, GRBVar> b = new java.util.HashMap<>();
        for (Surgery s : surgeries) {
            int m = s.index;
            int p = s.surgeryTypeIndex;
            if (!underageTypes.contains(p)) continue;
            for (int t = 0; t < Tnew; t++) {
                b.put(key(m, t), model.addVar(0.0, 1.0, 0.0, GRB.BINARY, null));
            }
        }

        model.update();

        // Objective 
        GRBLinExpr obj = new GRBLinExpr();
        // Total Overage
        for (GRBVar vmi : v.values()) {
            obj.addTerm(1.0, vmi);                       
        }
        // Opened trays
        for (GRBVar bmt : b.values()) {
            obj.addTerm(lambda2, bmt);                   
        }
        model.setObjective(obj, GRB.MINIMIZE);

        // Deshpande linearisation bounds on phi 
        for (int t = 0; t < Tnew; t++) {
            for (int p : underageTypes) {
                GRBVar atp = a.get(key(t, p));
                for (int i : suppliable) {
                    GRBVar phitpi = phi.get(key3(t, p, i));
                    GRBVar xti    = x.get(key(t, i));
                    if (phitpi == null || xti == null) continue;

                    // phi <= M * a
                    GRBLinExpr u1 = new GRBLinExpr();
                    u1.addTerm(1.0, phitpi); u1.addTerm(-M, atp);
                    model.addConstr(u1, GRB.LESS_EQUAL, 0.0, null);

                    // phi <= x + M(1 - a)
                    GRBLinExpr u2 = new GRBLinExpr();
                    u2.addTerm(1.0, phitpi); u2.addTerm(-1.0, xti); u2.addTerm(M, atp);
                    model.addConstr(u2, GRB.LESS_EQUAL, M, null);

                    // phi >= x - M(1 - a)
                    GRBLinExpr l1 = new GRBLinExpr();
                    l1.addTerm(1.0, phitpi); l1.addTerm(-1.0, xti); l1.addTerm(-M, atp);
                    model.addConstr(l1, GRB.GREATER_EQUAL, -M, null);
                }
            }
        }

        // First two constraints: overage and underage definition, substitute phi for a*x
        for (Surgery s : surgeries) {
            int m = s.index;
            int p = s.surgeryTypeIndex;

            java.util.Set<Integer> relevant = new java.util.HashSet<>(s.usedInstruments);
            for (int ti : s.existingTrays)
                for (int i = 0; i < K; i++)
                    if (trr.getXHat(ti, i) > 0) relevant.add(i);
            relevant.addAll(suppliable);

            // v's domain
            for (int i : relevant) {         
                GRBVar vmi = v.get(key(m, i));
                if (vmi == null) continue;                 
                GRBVar wmi = w.get(key(m, i));             

                // supplyNew = sum_t phi_{t,p,i}
                GRBLinExpr supplyNew = new GRBLinExpr();
                if (underageTypes.contains(p)) {
                    for (int t = 0; t < Tnew; t++) {
                        GRBVar ph = phi.get(key3(t, p, i));
                        if (ph != null) supplyNew.addTerm(1.0, ph);
                    }
                }

                int u = instance.getUsage(m, i);
                int xhatSum = 0;
                for (int t : s.existingTrays) xhatSum += trr.getXHat(t, i);

                // Overage: v >= xhatSum + supplyNew - u
                GRBLinExpr constraint1 = new GRBLinExpr();
                constraint1.addTerm(1.0, vmi);
                constraint1.multAdd(-1.0, supplyNew);
                model.addConstr(constraint1, GRB.GREATER_EQUAL, (double) (xhatSum - u), null);

                // Underage: w >= u - xhatSum - supplyNew
                if (wmi != null) {
                    GRBLinExpr constraint2 = new GRBLinExpr();
                    constraint2.addTerm(1.0, wmi);
                    constraint2.add(supplyNew);
                    model.addConstr(constraint2, GRB.GREATER_EQUAL, (double) (u - xhatSum), null);
                }
            }
        }

        // Constraint 3: underage constraint 
        for (int p = 0; p < P; p++) {
            GRBLinExpr lhs = new GRBLinExpr();
            for (int m : instance.getSurgeriesOfType(p)) {
                for (int i : Ihat) {
                    GRBVar wmi = w.get(key(m, i));
                    if (wmi != null) lhs.addTerm(1.0, wmi);
                }
            }
            double W1p = trr.W1[p];

            model.addConstr(lhs, GRB.LESS_EQUAL, (1 - beta2) * W1p, "serv[" + p + "]");
        }

        // Fourth and fifth constraints: opened tray definition, also use phi for linearisation 
        for (Surgery s : surgeries) {
            int m = s.index;
            int p = s.surgeryTypeIndex;
            if (!underageTypes.contains(p)) continue; 

            for (int t = 0; t < Tnew; t++) {
                GRBVar bmt = b.get(key(m, t));
                if (bmt == null) continue;

                // usedSupply = sum_{i in Î, delta_{m,i}=1} phi_{t,p(m),i}
                GRBLinExpr usedSupply = new GRBLinExpr();
                for (int i : suppliable) {
                    if (instance.isUsed(m, i)) {
                        GRBVar ph = phi.get(key3(t, p, i));
                        if (ph != null) usedSupply.addTerm(1.0, ph);
                    }
                }
                // Constraint 4: b <= usedSupply
                GRBLinExpr constraint4 = new GRBLinExpr();
                constraint4.addTerm(1.0, bmt); 
                constraint4.multAdd(-1.0, usedSupply);
                model.addConstr(constraint4, GRB.LESS_EQUAL, 0.0, null);

                // Constraint 5: Ihat*C*b >= usedSupply
                GRBLinExpr constraint5 = new GRBLinExpr();
                constraint5.addTerm((double) suppliable.size() * C, bmt);
                constraint5.multAdd(-1.0, usedSupply);
                model.addConstr(constraint5, GRB.GREATER_EQUAL, 0.0, null);
            }
        }

        // Do the optimisation 
        model.optimize();

        System.out.println("Add-on status: " + model.get(GRB.IntAttr.Status)
        + "  gap: " + model.get(GRB.DoubleAttr.MIPGap)
        + "  objective: " + model.get(GRB.DoubleAttr.ObjVal));

        int status = model.get(GRB.IntAttr.Status);
        if (status == GRB.INFEASIBLE || status == GRB.INF_OR_UNBD) {
            model.dispose();
            throw new GRBException("infeasible");   // caught in run(), see below
        }
        if (model.get(GRB.IntAttr.SolCount) == 0) {
            // Time limit hit with no feasible solution found, or other no-solution status
            model.dispose();
            throw new GRBException("no feasible solution within time limit");
        }

        // Extract solution (store nonzero only)
        AddOnTrayResult res = new AddOnTrayResult(N, K, P, trays.size());

        // x*_{t,i}: copies assigned to each new add-on tray, only i in Î
        for (int t = 0; t < Tnew; t++) {
            for (int i : suppliable) {
                GRBVar xti = x.get(key(t, i));
                if (xti != null) {
                    int val = (int) Math.round(xti.get(GRB.DoubleAttr.X));
                    if (val != 0) res.xStar.put(AddOnTrayResult.key(t, i), val);
                }
            }
        }

        // a*_{t,p}: assignment of add-on trays to surgery types, store only = 1 (true)
        for (int t = 0; t < Tnew; t++) {
            for (int p : underageTypes) {
                GRBVar atp = a.get(key(t, p));
                if (atp != null && atp.get(GRB.DoubleAttr.X) > 0.5) {
                    res.aStar.put(AddOnTrayResult.key(t, p), Boolean.TRUE);
                }
            }
        }

        // v̂*, ŵ* : overage and underage, only i in Î; accumulate totals
        for (Surgery s : surgeries) {
            int m = s.index;

            java.util.Set<Integer> relevant = new java.util.HashSet<>(s.usedInstruments);
            for (int ti : s.existingTrays) {
                for (int i = 0; i < K; i++) {
                    if (trr.getXHat(ti, i) > 0) relevant.add(i);
                }
            }
            relevant.addAll(suppliable);

            for (int i : relevant) {
                GRBVar vmi = v.get(key(m, i));
                if (vmi == null) continue;
                double vv = vmi.get(GRB.DoubleAttr.X);
                res.totalOverage += vv;
                if (vv > 1e-9) res.vStar.put(AddOnTrayResult.key(m, i), vv);

                GRBVar wmi = w.get(key(m, i));
                if (wmi != null) {
                    double ww = wmi.get(GRB.DoubleAttr.X);
                    res.totalUnderage += ww;
                    if (ww > 1e-6) res.wStar.put(AddOnTrayResult.key(m, i), ww);
                }
            }
        }

        // b*_{m,t}: opened new trays, store only true; count openings
        for (Surgery s : surgeries) {
            int m = s.index;
            if (!underageTypes.contains(s.surgeryTypeIndex)) continue; 
            for (int t = 0; t < Tnew; t++) {
                GRBVar bmt = b.get(key(m, t));
                if (bmt != null && bmt.get(GRB.DoubleAttr.X) > 0.5) {
                    res.bStar.put(AddOnTrayResult.key(m, t), Boolean.TRUE);
                    res.totalOpenedTrays++;
                }
            }
        }

        res.objective = model.get(GRB.DoubleAttr.ObjVal);
        model.dispose();

        return res;
    }

    private static long key3(int t, int p, int i) {
        return (((long) t) << 42) | (((long) p) << 21) | (i & 0x1FFFFFL);
    }

    private void printAddOnTrayStats(AddOnTrayResult aotr) {
        // Aggregate copies per new tray from x* (keyed by (t,i), t in high 32 bits)
        java.util.Map<Integer, Integer> sizePerTray = new java.util.HashMap<>();
        for (java.util.Map.Entry<Long, Integer> e : aotr.xStar.entrySet()) {
            int t = (int) (e.getKey() >>> 32);           // tray index
            sizePerTray.merge(t, e.getValue(), Integer::sum);
        }

        if (sizePerTray.isEmpty()) {
            System.out.println("\nAdd-on tray composition statistics: no add-on trays were created.");
            return;
        }

        int nTrays = sizePerTray.size();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long total = 0;
        for (int size : sizePerTray.values()) {
            total += size;
            if (size < min) min = size;
            if (size > max) max = size;
        }
        double mean = (double) total / nTrays;

        // Population standard deviation (divide by N), matching the reference figures
        double sumSq = 0.0;
        for (int size : sizePerTray.values()) {
            double d = size - mean;
            sumSq += d * d;
        }
        double std = Math.sqrt(sumSq / nTrays);

        System.out.println("\nAdd-on tray composition statistics:");
        System.out.printf("Average number of instruments in each tray          %.2f%n", mean);
        System.out.printf("Standard deviation of the number of instruments     %.2f%n", std);
        System.out.println("Minimum number of instruments in each tray          " + min);
        System.out.println("Maximum number of instruments in each tray          " + max);
    }

    public void writeResultsCsv(TrayInstance instance, TrayRationalisationResult trr, AddOnTrayResult aotr, File outFile) throws java.io.IOException {

        final List<Surgery> surgeries    = instance.getSurgeries();
        final List<Tray>    trays         = instance.getTrays();
        final List<Instrument> instruments = instance.getInstruments();
        final int K = instance.getNInstruments();

        // Build: for each add-on tray t, the single surgery type it's assigned to (if any).
        // a*_{t,p} is stored sparse (only =1). A tray may serve multiple types in principle;
        // we emit its instruments to every type it's assigned to.
        java.util.Map<Integer, java.util.List<Integer>> typesOfAddOnTray = new java.util.HashMap<>();
        for (long k : aotr.aStar.keySet()) {
            int t = (int) (k >>> 32);
            int p = (int) (k & 0xffffffffL);
            typesOfAddOnTray.computeIfAbsent(t, z -> new java.util.ArrayList<>()).add(p);
        }

        try (java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(outFile),java.nio.charset.StandardCharsets.UTF_8))) {
            // Header — exact input schema
            bw.write("net_definition,net_name,article_definition,article_name,cluster,treatment,treatment_code,surgery_id,tray_opened,used");
            bw.newLine();

            for (Surgery s : surgeries) {
                int m = s.index;
                int p = s.surgeryTypeIndex;
                String surgeryId    = s.surgeryID;
                String treatment    = instance.getSurgeryTypeTreatment(p);
                String treatmentCode= instance.getSurgeryTypeCode(p);

                // ---------- EXISTING trays assigned to this surgery ----------
                for (int ti : s.existingTrays) {
                    Tray tray = trays.get(ti);

                    // First pass: does this (m, tray) open? (any used instrument with positive kept copies)
                    boolean opened = false;
                    for (int i = 0; i < K; i++) {
                        if (trr.getXHat(ti, i) > 0 && instance.isUsed(m, i)) { opened = true; break; }
                    }
                    int trayOpened = opened ? 1 : 0;

                    // Second pass: emit ONE ROW PER COPY kept in this tray
                    for (int i = 0; i < K; i++) {
                        int copies = trr.getXHat(ti, i);
                        if (copies <= 0) continue;                       // instrument not kept in this tray

                        Instrument instr = instruments.get(i);

                        // How many of this instrument's copies did the surgery use? (capped at copies present)
                        int usedCopies = Math.min(instance.getUsage(m, i), copies);

                        // Emit one row per physical copy: first usedCopies rows are used=1, rest used=0
                        for (int cpy = 0; cpy < copies; cpy++) {
                            int used = (cpy < usedCopies) ? 1 : 0;
                            writeRow(bw,
                                tray.netID, tray.netName,
                                instr.articleID, instr.articleName,
                                instr.cluster, treatment, treatmentCode,
                                surgeryId, trayOpened, used);
                        }
                    }
                }

                // ---------- ADD-ON trays assigned to this surgery's TYPE ----------
                for (java.util.Map.Entry<Integer, java.util.List<Integer>> e : typesOfAddOnTray.entrySet()) {
                    int t = e.getKey();
                    if (!e.getValue().contains(p)) continue;   // this add-on tray isn't assigned to surgery m's type

                    String addonNet  = "ADDON_" + t;
                    String addonName = "ADDON_" + t;           // placeholder net_name

                    // First pass: opened for this (m, add-on tray)?
                    boolean opened = false;
                    for (int i = 0; i < K; i++) {
                        if (aotr.getXStar(t, i) > 0 && instance.isUsed(m, i)) { opened = true; break; }
                    }
                    int trayOpened = opened ? 1 : 0;

                    // Second pass: ONE ROW PER COPY the add-on tray carries
                    for (int i = 0; i < K; i++) {
                        int copies = aotr.getXStar(t, i);
                        if (copies <= 0) continue;

                        Instrument instr = instruments.get(i);
                        int usedCopies = Math.min(instance.getUsage(m, i), copies);

                        for (int cpy = 0; cpy < copies; cpy++) {
                            int used = (cpy < usedCopies) ? 1 : 0;
                            writeRow(bw,
                                addonNet, addonName,
                                instr.articleID, instr.articleName,
                                instr.cluster, treatment, treatmentCode,
                                surgeryId, trayOpened, used);
                        }
                    }
                }
            }
        }

        System.out.println("Finished writing results to " + outFile);
    }

    /** Write one CSV row, quoting any field that needs it. */
    private static void writeRow(java.io.BufferedWriter bw,
                                String netDef, String netName,
                                String artDef, String artName,
                                int cluster, String treatment, String treatmentCode,
                                String surgeryId, int trayOpened, int used) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(csv(netDef)).append(',')
        .append(csv(netName)).append(',')
        .append(csv(artDef)).append(',')
        .append(csv(artName)).append(',')
        .append(cluster).append(',')
        .append(csv(treatment)).append(',')
        .append(csv(treatmentCode)).append(',')
        .append(csv(surgeryId)).append(',')
        .append(trayOpened).append(',')
        .append(used);
        bw.write(sb.toString());
        bw.newLine();
    }

    /** Quote a field if it contains comma, quote, or newline; escape embedded quotes by doubling. */
    private static String csv(String s) {
        if (s == null) return "";
        s = s.replace("\r", " ").replace("\n", " ");   // flatten any embedded line breaks
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0;
        if (!needsQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private double[] printPhysicalMetrics(TrayInstance instance, TrayRationalisationResult trr, AddOnTrayResult aotr) {
        final List<Surgery> surgeries = instance.getSurgeries();
        final int K = instance.getNInstruments();

        // add-on tray -> types it's assigned to
        java.util.Map<Integer, java.util.List<Integer>> typesOfAddOn = new java.util.HashMap<>();
        for (long k : aotr.aStar.keySet()) {
            int t = (int) (k >>> 32);
            int p = (int) (k & 0xffffffffL);
            typesOfAddOn.computeIfAbsent(t, z -> new java.util.ArrayList<>()).add(p);
        }

        long overage = 0, underage = 0;
        int nSurgeries = surgeries.size();
        long nOpenedTrays = 0;

        for (Surgery s : surgeries) {
            int m = s.index;
            int p = s.surgeryTypeIndex;

            // ---- Determine which trays are OPENED for this surgery (compute ONCE, not per instrument) ----
            // A tray is opened iff the surgery uses >= 1 instrument the tray supplies.

            // existing trays opened for m
            java.util.List<Integer> openedExisting = new java.util.ArrayList<>();
            for (int ti : s.existingTrays) {
                boolean opened = false;
                for (int j = 0; j < K; j++) {
                    if (trr.getXHat(ti, j) > 0 && instance.isUsed(m, j)) {
                         opened = true; 
                         nOpenedTrays += 1;
                         break; 
                        }
                }
                if (opened) openedExisting.add(ti);
            }

            // add-on trays opened for m (must be assigned to m's type AND supply something m uses)
            java.util.List<Integer> openedAddOn = new java.util.ArrayList<>();
            for (var e : typesOfAddOn.entrySet()) {
                int t = e.getKey();
                if (!e.getValue().contains(p)) continue;
                boolean opened = false;
                for (int j = 0; j < K; j++) {
                    if (aotr.getXStar(t, j) > 0 && instance.isUsed(m, j)) { 
                        opened = true;
                        nOpenedTrays += 1;
                        break; }
                }
                if (opened) openedAddOn.add(t);
            }

            // ---- Per instrument: supply from OPENED trays only, compare to usage ----
            for (int i = 0; i < K; i++) {
                int demand = instance.getUsage(m, i);   // copies the surgery used

                int supply = 0;
                for (int ti : openedExisting) supply += trr.getXHat(ti, i);
                for (int t  : openedAddOn)   supply += aotr.getXStar(t, i);

                // surplus = present-in-opened-trays - used ; positive -> overage, negative -> underage
                if (supply > demand) overage  += (supply - demand);
                else if (demand > supply) underage += (demand - supply);
            }
        }

        double finalOverage = (double) overage;
        double finalUnderage = (double) underage;
        double finalOpenedTrays = (double) nOpenedTrays;

        System.out.println("\nPhysical metrics (matches Python cross-evaluation):");
        System.out.printf("Overage per surgery  : %.4f%n", finalOverage  / nSurgeries);
        System.out.printf("Underage per surgery : %.4f%n", finalUnderage / nSurgeries);
        System.out.printf("Opened trays per surgery : %.4f%n", finalOpenedTrays / nSurgeries);

        return new double[] {finalOverage, finalUnderage, finalOpenedTrays};
    }
}
