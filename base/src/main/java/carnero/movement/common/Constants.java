package carnero.movement.common;

public class Constants {

    public static final String TAG = "carnero.movement";
    public static final int ID_NOTIFICATION_SERVICE = 1001;

    // Teleport paths
    public static final String PATH_RESOLUTION = "/resolution";

    // Foursquare
    public static final String FSQ_CLIENT_ID = "YZSFMTADI4H1JTHAGCMPQX3PUJ4SYAWKRL2K5SCTN5NRD432";
    public static final String FSQ_CLIENT_SECRET = "E01G3OH44K3NIUVBZG3Z0ABPS3AL3XJMBWECL1LAGW1OPO0I";

    // Metrics
    public static final float STEP_LENGTH_WALK = 0.6f; // metres
    public static final float STEP_LENGTH_RUN = 1.3f;
    public static final int CADENCE_WALK_MIN = 30; // steps per minute
    public static final int CADENCE_RUN_MIN = 140;
    public static final int CADENCE_RUN_MAX = 190;
}
