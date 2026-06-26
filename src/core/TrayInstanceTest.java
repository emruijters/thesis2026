package core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Check if csv file was read correctly
 */
public class TrayInstanceTest {

    public static void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            File fileToRead = new File("data/simulatedData/instance0" +  i + ".csv");

            try {
                System.out.println("Reading file: " + fileToRead);
                TrayInstance instance = TrayInstance.read(fileToRead);

                // Test if instance is read correctly 
                System.out.println("Number of surgeries is " + instance.getNSurgeries());
                System.out.println("Number of surgery types is " + instance.getNSurgeryTypes());
                System.out.println("Number of instruments is " + instance.getNInstruments());
                System.out.println("Number of trays is " + instance.getNTrays());

                /*
                for (Surgery surgery : instance.getSurgeries()) {
                    System.out.println(
                        "Surgery " + surgery.surgeryID +
                        " (type " + instance.getSurgeryTypeCode(surgery.surgeryTypeIndex) + ")" +
                        " has T0 " + trayNames(instance, surgery.existingTrays) +
                        ", opened " + trayNames(instance, surgery.openedTrays) +
                        ", closed " + trayNames(instance, surgery.closedTrays) +
                        " and uses " + surgery.usedInstruments.size() + " instrument types"
                    );
                }
                */

                /*
                for (Surgery surgery : instance.getSurgeries()) {
                    System.out.println(
                        "Surgery " + surgery.surgeryID +
                        " (type " + surgery.surgeryTypeIndex + ")" +
                        " has T0 " + surgery.existingTrays +
                        ", opened " + surgery.openedTrays +
                        ", closed " + surgery.closedTrays +
                        " and uses " + surgery.usedInstruments.size() + " instrument types"
                    );
                }
                */
    
                /*
                int traycount = 0;
                for (Tray tray : instance.getTrays()) {
                    System.out.println(
                        "Tray " + tray.netId +
                        " has n_t " + tray.nCopies
                    );
                    traycount++;
                }
                System.out.println("Total number of trays: " + traycount);
                 */
                
            } catch (IOException ex) {
            System.out.println("There was an error reading file " + fileToRead);
            ex.printStackTrace();
            }
        }
        //File fileToRead = new File("data/simulatedData/instance00.csv");
    }

    // Map a list of tray indices to their net_definition names.
    private static List<String> trayNames(TrayInstance instance, List<Integer> trayIndices) {
        List<String> names = new ArrayList<>();
        for (int t : trayIndices) {
            names.add(instance.getTrays().get(t).netId);
        }
        return names;
    }
}
