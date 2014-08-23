package carnero.movement.ui;

import java.util.ArrayList;

import carnero.movement.R;

public class DistanceActivity extends AbstractGraphActivity {

    protected int getLineColor() {
        return getResources().getColor(R.color.graph_distance);
    }

    protected ArrayList<Double> getValues() {
        return mContainer.distanceList;
    }
}
