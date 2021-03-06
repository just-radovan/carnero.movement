package carnero.movement.graph;

import android.content.res.Resources;

import carnero.movement.common.R;
import carnero.movement.common.graph.SplinePath;

public class DistancePath extends SplinePath {

    @Override
    public void init(Resources resources) {
        mFillPath = true;
        mStrokeWidthRes = R.dimen.graph_stroke;
        mPathColorRes = R.color.graph_distance;

        super.init(resources);
    }
}
