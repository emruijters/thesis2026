package MILP;

public class FinalResult {
    public final int phase0;
    public final double beta1;
    public final double beta2;
    public final double lambda1;
    public final double lambda2;
    public final int T;
    public final int C;
    public final double overage;
    public final double underage;
    public final double traysOpened;
    public final long runtime;

    FinalResult(int phase0, double beta1, double beta2, double lambda1, double lambda2, int T, int C, double overage, double underage, double traysOpened, long runtime) {
        this.phase0 = phase0;
        this.beta1 = beta1;
        this.beta2 = beta2;
        this.lambda1 = lambda1;
        this.lambda2 = lambda2;
        this.T = T;
        this.C = C;
        this.overage = overage;
        this.underage = underage;
        this.traysOpened = traysOpened;
        this.runtime = runtime;
    }
}
