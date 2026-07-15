package core;

/**
 * Holds an instrument type
 */
public class Instrument {
    public final int index;
    public final String articleID;
    public final String articleName;
    public final int cluster; 

    /**
     * Constructor. 
     * 
     * @param index index of the instrument type 
     * @param articleID ID of the instrument 
     * @param articleName name of the instrument
     * @param cluster the cluster the instrument belongs to 
     */
    public Instrument(int index, String articleID, String articleName, int cluster) {
        this.index = index;
        this.articleID = articleID;
        this.articleName = articleName;
        this.cluster = cluster;
    }
}
