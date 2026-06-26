package core;

// Packages used
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class that makes a TrayInstance for the surgical tray optimisation problem. 
 * Reads a long-format CSV, where every row is one surgery-tray-instrument triple
 */
public class TrayInstance {
    private final List<Surgery> surgeries;
    private final List<Tray> trays;
    private final List<Instrument> instruments;

    // usage[m][i] = u_{m,i}
    private final int[][] usage;
    
    // S(p) 
    private final List<List<Integer>> surgeriesOfType; 
    private final int nSurgeryTypes; 

    private final String[] surgeryTypeCodes;

    /**
     * Constructor for TrayInstance. 
     * 
     * @param surgeries list of surgeries in the instance
     * @param trays list of existing trays for the instance
     * @param instruments list of instruments for the instance
     * @param usage instrument usage
     * @param surgeriesOfType surgeries of various surgery types
     * @param nSurgeryTypes number of surgery types 
     * @param surgeryTypeCodes original treatment_code string per surgery type index
     */
    public TrayInstance(List<Surgery> surgeries, List<Tray> trays, List<Instrument> instruments, int[][] usage, List<List<Integer>> surgeriesOfType, int nSurgeryTypes, String[] surgeryTypeCodes) {
        this.surgeries = surgeries;
        this.trays = trays;
        this.instruments = instruments;
        this.usage = usage;
        this.surgeriesOfType = surgeriesOfType;
        this.nSurgeryTypes = nSurgeryTypes;
        this.surgeryTypeCodes = surgeryTypeCodes;
    }

    // Get methods 
    public List<Surgery> getSurgeries() {
        return Collections.unmodifiableList(surgeries);
    }
 
    public List<Tray> getTrays() {
        return Collections.unmodifiableList(trays);
    }
 
    public List<Instrument> getInstruments() {
        return Collections.unmodifiableList(instruments);
    }
 
    public int getNSurgeries()   {
         return surgeries.size(); 
    }   

    public int getNTrays() {
        return trays.size();
    }
    
    public int getNInstruments() {
        return instruments.size(); 
    }  
    public int getNSurgeryTypes() {
        return nSurgeryTypes; 
    } 

    // Usage count of instrument i in surgery m 
    public int getUsage(int m, int i) {
        return usage[m][i];
    }

    // delta_{m,i}: true if instrument i was used in surgery m 
    public boolean isUsed(int m, int i) {
        return usage[m][i] > 0;
    }

    // S(p) = { m : p(m) = p }
    public List<Integer> getSurgeriesOfType(int p) {
        return Collections.unmodifiableList(surgeriesOfType.get(p));
    }

    // Get original treatment_code for surgery type
    public String getSurgeryTypeCode(int p) {
        return surgeryTypeCodes[p];
    }

    /**
     * Construct a TrayInstance by reading from a CSV file.
     *
     * @param instanceFileName file name
     * @return TrayInstance with surgeries, trays and instruments read from file
     * @throws IOException IOException
     */
    public static TrayInstance read(File instanceFileName) throws IOException {
 
        // First-seen-order maps from CSV key -> dense index.
        Map<String, Integer> surgeryIdx     = new LinkedHashMap<>();
        Map<String, Integer> surgeryTypeIdx = new LinkedHashMap<>();
        Map<String, Integer> trayIdx        = new LinkedHashMap<>();
        Map<String, Integer> instrumentIdx  = new LinkedHashMap<>();
 
        // Accumulators (sparse), densified after the file is fully read.
        Map<Integer, Integer> typeOfSurgery       = new HashMap<>();      
        Map<Integer, Integer> clusterOfInstrument = new HashMap<>();     
        Map<Long, Integer>    usageMap            = new HashMap<>();      
         Map<Long, Map<Integer, Integer>> compPerSurgery = new HashMap<>();      
        Map<Long, Boolean>    openedMap           = new HashMap<>();      
        Map<Integer, List<Integer>> traysSeen     = new LinkedHashMap<>();
 
        try (BufferedReader br = new BufferedReader(new FileReader(instanceFileName))) {
            String line;
 
            // Read header and resolve column positions by name.
            line = br.readLine();
            if (line == null) {
                return new TrayInstance(new ArrayList<>(), new ArrayList<>(),
                        new ArrayList<>(), new int[0][0], new ArrayList<>(), 0, new String[0]);
            }
            
            Map<String, Integer> col = headerPositions(line);
 
            int cNet     = require(col, "net_definition");
            int cArticle = require(col, "article_definition");
            int cSurgery = require(col, "surgery_id");
            int cOpened  = require(col, "tray_opened");
            int cUsed    = require(col, "used");
            int cCluster = require(col, "cluster");
            int cType    = require(col, "treatment_code"); 
            int cTreatment = require(col, "treatment");

            // Loop over all rows to obtain the triples.
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
 
                String[] c = splitCsv(line);
 
                int m = intern(surgeryIdx, c[cSurgery].trim());
                
                // Use treatment_code as type; if that is empty, use treatment as type
                String typeKey = c[cType].trim();
                if (typeKey.isEmpty()) {
                    typeKey = c[cTreatment].trim();
                }
                
                int p = intern(surgeryTypeIdx, typeKey);
                int t = intern(trayIdx, c[cNet].trim());
                int i = intern(instrumentIdx, c[cArticle].trim());
 
                // p(m): each row of a surgery must carry the same surgery type.
                Integer prev = typeOfSurgery.putIfAbsent(m, p);
                if (prev != null && prev != p) {
                    System.out.println("Warning: surgery " + c[cSurgery]
                            + " maps to more than one surgery type.");
                }
 
                // cluster of the instrument
                int clusterVal = parseIntSafe(c[cCluster]);
                Integer prevCluster = clusterOfInstrument.putIfAbsent(i, clusterVal);
                if (prevCluster != null && prevCluster != clusterVal) {
                    //System.out.println("Warning: instrument " + c[cArticle] + " maps to more than one cluster.");
                }
 
                // get u_{m,i} (sum)
                int usedVal = parseIntSafe(c[cUsed]);
 
                long mi = pack(m, i);
                usageMap.merge(mi, usedVal, Math::max);
 
                // Composition: count number of copies (t,i) within the same surgery.
                long ti = pack(t, i);
                compPerSurgery
                        .computeIfAbsent(ti, k -> new HashMap<>())
                        .merge(m, 1, Integer::sum);
 
                // Observed presence + opened flag for (m,t).
                int openedFlag = parseIntSafe(c[cOpened]);
                long mt = pack(m, t);
                openedMap.merge(mt, openedFlag != 0, Boolean::logicalOr);
                traysSeen.computeIfAbsent(m, k -> new ArrayList<>());
                if (!traysSeen.get(m).contains(t)) {
                    traysSeen.get(m).add(t);
                }
            }
        }
 
        int nSurgeries     = surgeryIdx.size();
        int nSurgeryTypes  = surgeryTypeIdx.size();
        int nTrays         = trayIdx.size();
        int nInstruments   = instrumentIdx.size();
 
        // Build Instrument list
        List<Instrument> instruments = new ArrayList<>(nInstruments);
        for (Map.Entry<String, Integer> e : instrumentIdx.entrySet()) {
            int i = e.getValue();
            int cluster = clusterOfInstrument.getOrDefault(i, -1);
            instruments.add(new Instrument(i, e.getKey(), cluster));
        }
        instruments.sort(java.util.Comparator.comparingInt(x -> x.index));
 
        // Build Tray list 
        int[][] comp = new int[nTrays][nInstruments];
        for (Map.Entry<Long, Map<Integer, Integer>> e : compPerSurgery.entrySet()) {
            long key = e.getKey();
            int maxCopies = 0;
            for (int copies : e.getValue().values()) {
                if (copies > maxCopies) maxCopies = copies;
            }
            comp[hi(key)][lo(key)] = maxCopies;
        }
        List<Tray> trays = new ArrayList<>(nTrays);
        for (Map.Entry<String, Integer> e : trayIdx.entrySet()) {
            int t = e.getValue();
            trays.add(new Tray(t, e.getKey(), comp[t]));
        }
        trays.sort(java.util.Comparator.comparingInt(x -> x.index));
 
        // Densify usage matrix 
        int[][] usage = new int[nSurgeries][nInstruments];
        for (Map.Entry<Long, Integer> e : usageMap.entrySet()) {
            long key = e.getKey();
            usage[hi(key)][lo(key)] = e.getValue();
        }
 
        // S(p) 
        List<List<Integer>> surgeriesOfType = new ArrayList<>(nSurgeryTypes);
        for (int p = 0; p < nSurgeryTypes; p++) {
            surgeriesOfType.add(new ArrayList<>());
        } 
 
        // Build list of surgeries 
        List<Surgery> surgeries = new ArrayList<>(nSurgeries);
        for (Map.Entry<String, Integer> e : surgeryIdx.entrySet()) {
            int m = e.getValue();
            int p = typeOfSurgery.getOrDefault(m, 0);
 
            List<Integer> t0  = traysSeen.getOrDefault(m, new ArrayList<>());
            List<Integer> t0o = new ArrayList<>();
            List<Integer> t0c = new ArrayList<>();
            for (int t : t0) {
                if (Boolean.TRUE.equals(openedMap.get(pack(m, t)))) {
                    t0o.add(t);
                } else {
                    t0c.add(t);
                }
            }
            List<Integer> usedInstr = new ArrayList<>();
            for (int i = 0; i < nInstruments; i++) {
                if (usage[m][i] > 0) usedInstr.add(i);
            }
 
            surgeries.add(new Surgery(m, e.getKey(), p, t0, t0o, t0c, usedInstr));
            surgeriesOfType.get(p).add(m);
        }
        surgeries.sort(java.util.Comparator.comparingInt(x -> x.index));
 
        // Get surgery type codes
        String[] surgeryTypeCodes = new String[nSurgeryTypes];
        for (Map.Entry<String, Integer> e : surgeryTypeIdx.entrySet()) {
            surgeryTypeCodes[e.getValue()] = e.getKey();
        }

        // Create instance with the obtained information
        return new TrayInstance(surgeries, trays, instruments, usage,
                surgeriesOfType, nSurgeryTypes, surgeryTypeCodes);
    }

    // header / parsing helpers
    private static Map<String, Integer> headerPositions(String headerLine) {
        String[] cols = splitCsv(headerLine);
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < cols.length; i++) {
            pos.put(cols[i].trim().toLowerCase(), i);
        }
        return pos;
    }

    private static String[] splitCsv(String line) {
        java.util.List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int k = 0; k < line.length(); k++) {
            char ch = line.charAt(k);
            if (inQuotes) {
                if (ch == '"') {
                    if (k + 1 < line.length() && line.charAt(k + 1) == '"') {
                        cur.append('"');   // escaped quote ""
                        k++;
                    } else {
                        inQuotes = false;  // closing quote
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;           // opening quote
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
 
    private static int require(Map<String, Integer> pos, String name) {
        Integer i = pos.get(name);
        if (i == null) {
            throw new IllegalArgumentException("CSV missing column: " + name);
        }
        return i;
    }
 
    private static int intern(Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i != null) return i;
        int newIdx = idx.size();
        idx.put(key, newIdx);
        return newIdx;
    }
 
    private static int parseIntSafe(String s) {
        s = s.trim();
        return s.isEmpty() ? 0 : Integer.parseInt(s);
    }
 
    // Pack two non-negative ints into one long key for the sparse maps.
    private static long pack(int hi, int lo) {
        return (((long) hi) << 32) | (lo & 0xffffffffL);
    }
    private static int hi(long key) {
        return (int) (key >>> 32); 
    }
    private static int lo(long key) {
        return (int) key; 
    }
}
