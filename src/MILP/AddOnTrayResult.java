package MILP;

import java.util.HashMap;
import java.util.Map;

public class AddOnTrayResult {
    // x*_{t,i}: copies assigned to new add on trays, keyed by (t,i)
    public final Map<Long, Integer> xStar = new HashMap<>();
    // a*_{t,p}: the assignment of add-on trays to types of treatment, keyed by (t,p), only true entries stored
    public final Map<Long, Boolean> aStar = new HashMap<>();
    // Overage, keyed by (m,i)
    public final Map<Long, Double> vStar = new HashMap<>();
    // Underage, keyed by (m,i)
    public final Map<Long, Double> wStar = new HashMap<>(); 
    // Tray opened, keyed by (m,t), only true entries stored
    public final Map<Long, Boolean> bStar = new HashMap<>();
 
    public double totalOverage;
    public double totalUnderage;
    public int    totalOpenedTrays;
    public double objective;
 
    // N, K, nTrays no longer size any array; kept for reference / bounds if needed.
    public final int N, K, P, nTrays;
 
    AddOnTrayResult(int N, int K, int P, int nTrays) {
        this.N = N; this.K = K; this.P = P; this.nTrays = nTrays;
    }

    // Key packing 
    public static long key(int a, int b) {
        return (((long) a) << 32) | (b & 0xffffffffL);
    }

    // Accessors 
    public int getXStar(int t, int i) {
        return xStar.getOrDefault(key(t, i), 0);
    }
    public boolean isAssigned(int t, int p) {
        return aStar.getOrDefault(key(t,p), Boolean.FALSE);
    }
    public double getVStar(int m, int i) {
        return vStar.getOrDefault(key(m, i), 0.0);
    }
    public double getWStar(int m, int i) {
        return wStar.getOrDefault(key(m, i), 0.0);
    }
    public boolean isOpened(int m, int t) {
        return bStar.getOrDefault(key(m, t), Boolean.FALSE);
    }
}
