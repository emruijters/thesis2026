package core;

/**
 * Holds an existing tray. 
 */
public class Tray {
    
    public final int index;     
    public final String netId; 
    public final int[] composition; 
    public final int nCopies;   
 
    /**
     * Constructor 
     * 
     * @param index tray index 
     * @param netId net definition
     * @param composition composition of the tray 
     */
    public Tray(int index, String netId, int[] composition) {
        this.index = index;
        this.netId = netId;
        this.composition = composition;
        int total = 0;
        for (int copy : composition) {
            total += copy;
        }
        
        this.nCopies = total;
    }
}
