package core;

import java.util.List;

/**
 * Holds a specific surgery.
 */
public class Surgery {
    public final int index;
    public final String surgeryID;
    public final int surgeryTypeIndex;

    public final List<Integer> existingTrays;
    public final List<Integer> openedTrays;   
    public final List<Integer> closedTrays;   
    public final List<Integer> usedInstruments;

    /**
     * Constructor 
     * 
     * @param index surgery index
     * @param surgeryID surgery ID 
     * @param surgeryTypeIndex index of the surgery type 
     * @param existingTrays list of trays assigned to the surgery (T0(m))
     * @param openedTrays list of trays opened during the surgery
     * @param closedTrays list of trays closed during the surgery 
     * @param usedInstruments type of instruments used during the surgery (delta_{m,i} = 1) 
     */
    public Surgery(int index, String surgeryID, int surgeryTypeIndex, List<Integer> existingTrays, List<Integer> openedTrays, List<Integer> closedTrays, List<Integer> usedInstruments) {
        this.index = index;
        this.surgeryID = surgeryID;
        this.surgeryTypeIndex = surgeryTypeIndex;

        this.existingTrays = existingTrays;
        this.openedTrays = openedTrays;
        this.closedTrays = closedTrays;
        this.usedInstruments = usedInstruments;
    }
}
