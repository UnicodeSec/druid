package io.druid.query.search.search;

import com.metamx.emitter.EmittingLogger;
import io.druid.collections.bitmap.ImmutableBitmap;
import io.druid.query.dimension.DimensionSpec;
import io.druid.segment.QueryableIndex;
import io.druid.segment.Segment;

import java.util.List;

public class AutoStrategy extends SearchStrategy
{
  public static final String NAME = "auto";

  private static final EmittingLogger log = new EmittingLogger(AutoStrategy.class);

  public AutoStrategy(SearchQuery query)
  {
    super(query);
  }

  @Override
  public List<SearchQueryExecutor> getExecutionPlan(SearchQuery query, Segment segment)
  {
    final QueryableIndex index = segment.asQueryableIndex();

    if (index != null) {
      final ImmutableBitmap timeFilteredBitmap = IndexOnlyStrategy.makeTimeFilteredBitmap(index,
                                                                                          segment,
                                                                                          filter,
                                                                                          interval);
      final Iterable<DimensionSpec> dimsToSearch = getDimsToSearch(index.getAvailableDimensions(),
                                                                   query.getDimensions());

      // Choose a search query execution strategy depending on the query.
      // Index-only strategy is selected when
      // 1) there is no filter,
      // 2) the total cardinality is very low, or
      // 3) the filter has a very low selectivity.
      final SearchQueryDecisionHelper helper = getDecisionHelper(index);
      if (filter == null ||
          helper.hasLowCardinality(index, dimsToSearch) ||
          helper.hasLowSelectivity(index, timeFilteredBitmap)) {
        log.debug("Index-only execution strategy is selected");
        return new IndexOnlyStrategy(query, timeFilteredBitmap).getExecutionPlan(query, segment);
      } else {
        log.debug("Cursor-based execution strategy is selected");
        return new CursorBasedStrategy(query).getExecutionPlan(query, segment);
      }

    } else {
      log.debug("Index doesn't exist. Fall back to cursor-based execution strategy");
      return new CursorBasedStrategy(query).getExecutionPlan(query, segment);
    }
  }
}
