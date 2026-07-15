package MILP;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TrayRationalisationResult {
    // x̂_{t,i}: copies kept in existing trays, keyed by (t,i)
    public final Map<Long, Integer> xHat = new HashMap<>();
    // Overage, keyed by (m,i)
    public final Map<Long, Double> vHat = new HashMap<>();
    // Underage, keyed by (m,i)
    public final Map<Long, Double> wHat = new HashMap<>(); 
    // Tray opened, keyed by (m,t), only true entries stored
    public final Map<Long, Boolean> bHat = new HashMap<>();
    // W1_p: underage per surgery type
    public final double[]    W1;     
    // Instruments with positive underage
    public final List<Integer> Ihat = new ArrayList<>(); 
 
    public double totalOverage;
    public double totalUnderage;
    public int    totalOpenedTrays;
    public double objective;
 
    // N, K, nTrays no longer size any array; kept for reference / bounds if needed.
    public final int N, K, P, nTrays;
 
    TrayRationalisationResult(int N, int K, int P, int nTrays) {
        this.N = N; this.K = K; this.P = P; this.nTrays = nTrays;
        this.W1 = new double[P];
    }

    // Key packing 
    public static long key(int a, int b) {
        return (((long) a) << 32) | (b & 0xffffffffL);
    }

    // Accessors 
    public int getXHat(int t, int i) {
        return xHat.getOrDefault(key(t, i), 0);
    }
    public double getVHat(int m, int i) {
        return vHat.getOrDefault(key(m, i), 0.0);
    }
    public double getWHat(int m, int i) {
        return wHat.getOrDefault(key(m, i), 0.0);
    }
    public boolean isOpened(int m, int t) {
        return bHat.getOrDefault(key(m, t), Boolean.FALSE);
    }

    // Get overage excluding the instruments in Ihat
    public double overageExcludingInstruments(java.util.Set<Integer> excluded) {
    double sum = 0.0;
    for (Map.Entry<Long, Double> e : vHat.entrySet()) {
        int i = (int) (e.getKey() & 0xffffffffL);   
        if (!excluded.contains(i)) {
            sum += e.getValue();
        } 
    }
    return sum;
}
}
