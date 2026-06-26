package core;

/**
 * Holds an instrument type
 */
public class Instrument {
    public final int index;
    public final String articleID;
    public final int cluster; 

    /**
     * Constructor. 
     * 
     * @param index index of the instrument type 
     * @param articleID name of the instrument 
     * @param cluster the cluster the instrument belongs to 
     */
    public Instrument(int index, String articleID, int cluster) {
        this.index = index;
        this.articleID = articleID;
        this.cluster = cluster;
    }
}
