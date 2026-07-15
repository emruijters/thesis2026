package MILP;
import java.io.File;
import java.io.IOException;

import com.gurobi.gurobi.*;

import core.TrayInstance;

public class SolveDecomposed {

    public static void main(String[] args) throws GRBException {
        // System.out.println("Max heap (MB): " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)));

        for (int i = 0; i < 1; i++) {
            File fileToRead = new File("data/TraysKnown/simulatedData/instance0" +  i + ".csv");

            try {
                System.out.println("\nSolving instance0" + i + ":");
                
                TrayInstance instance = TrayInstance.read(fileToRead);
                
                // Set parameters
                double lambda1 = 0.0;
                int T = 50;
                int C = 100;

                // beta1: < 0.50 runs into problems; beta2 same as paper
                double[] beta1s = {1.0, 0.75, 0.50, 0.40};
                double[] beta2s = {1.0, 0.75, 0.50, 0.25, 0.10, 0.00};
                double[] lambda2s = {0.0};

                boolean[] addPhase0 = {false, true};

                File outFile = new File("data/TraysKnown/results/decomposition/instance0" + i + "_parameterTuning_decomp.csv");

                // Open csv writer 
                try (java.io.BufferedWriter bw = openResultsCsv(outFile)) {

                    for (boolean phase0 : addPhase0) {
                        for (double beta1 : beta1s) {
                            for (double beta2 : beta2s) {
                                for (double lambda2 : lambda2s) {
                                    int phase0int = 0;
                                    if (phase0) {
                                        phase0int += 1;
                                    }
                                    // Print the current parameters 
                                    System.out.println("\nParameters:");
                                    if (phase0) {
                                        System.out.println("\nPhase0 implemented.");
                                    }
                                    System.out.printf("beta1=%.2f  beta2=%.2f  lambda1=%.2f  lambda2=%.2f  T=%2d C=%3d",
                                                            beta1, beta2, lambda1, lambda2, T, C);
                                    
                                    System.out.println("");

                                    // Cutoff decides the W0p
                                    double cutoff = 0.10;

                                    // Get the solver and solve it
                                    Solver solver = new Solver(instance, phase0, beta1, beta2, lambda1, lambda2, T, C, cutoff); 
                                    // SolverDeshpande solver = new SolverDeshpande(instance, beta1, beta2, T, C, cutoff);

                                    try {
                                        long startRunTime = System.currentTimeMillis();
                                        double[] result = solver.run();
                                        long endRunTime = System.currentTimeMillis();
                                        long runtime = endRunTime - startRunTime;

                                        // Collect final result and write to csv file
                                        FinalResult finalResult = new FinalResult(phase0int, beta1, beta2, lambda1, lambda2, T, C, result[0], result[1], result[2], runtime);
                                        appendResult(bw, finalResult);

                                    } catch (Exception e) {
                                        System.out.println("  -> Unexpected error for this parameter set, continuing: " + e.getMessage());
                                        try {
                                            appendResult(bw, new FinalResult(phase0int, beta1, beta2, lambda1, lambda2, T, C,
                                                    Double.NaN, Double.NaN, Double.NaN, -1L));
                                        } catch (IOException io) { /* ignore */ }
                                    }

                                    System.out.println("\nFinished.");
                                }
                            }
                        }
                    }
                }
                System.out.println("Wrote results to " + outFile);

            } catch (IOException ex) {
            System.out.println("There was an error reading file " + fileToRead);
            ex.printStackTrace();
            }
        }
    }

    /** Open a fresh CSV for one instance and write the header. Returns the open writer. */
    private static java.io.BufferedWriter openResultsCsv(File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();

        java.io.BufferedWriter bw = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(outFile),
                    java.nio.charset.StandardCharsets.UTF_8));
        bw.write("phase0,beta1,beta2,lambda1,lambda2,T,C,overage,underage,traysOpened,runtime_ms");
        bw.newLine();
        bw.flush();
        return bw;
    }

    /** Append one result line and flush immediately so it's on disk right away. */
    private static void appendResult(java.io.BufferedWriter bw, FinalResult r) throws IOException {
        bw.write(String.format(java.util.Locale.US,
                "%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%.2f,%.2f,%.2f,%d",
                r.phase0, r.beta1, r.beta2, r.lambda1, r.lambda2,
                r.T, r.C,
                r.overage, r.underage, r.traysOpened,
                r.runtime));
        bw.newLine();
        bw.flush();   
    }
}
