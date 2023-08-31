package io.confluent.developer.query;

import io.confluent.developer.model.StockTransactionAggregation;
import io.confluent.developer.streams.SerdeUtil;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.query.Query;
import org.apache.kafka.streams.query.RangeQuery;
import org.apache.kafka.streams.state.KeyValueIterator;

public class QueryUtils {

    private QueryUtils(){}

    public static Query<KeyValueIterator<String, StockTransactionAggregation>> createRangeQuery(String lower, String upper, String jsonPredicate) {
        if (isNotBlank(jsonPredicate)) {
            return createFilteredRangeQuery(lower, upper, jsonPredicate);
        } else {
            if (isBlank(lower) && isBlank(upper)) {
                return RangeQuery.withNoBounds();
            } else if (!isBlank(lower) && isBlank(upper)) {
                return RangeQuery.withLowerBound(lower);
            } else if (isBlank(lower) && !isBlank(upper)) {
                return RangeQuery.withUpperBound(upper);
            } else {
                return RangeQuery.withRange(lower, upper);
            }
        }
    }

    public static FilteredRangeQuery<String, StockTransactionAggregation> createFilteredRangeQuery(String lower, String upper, String jsonPredicate) {
        return FilteredRangeQuery.<String, StockTransactionAggregation>withBounds(lower, upper)
                .predicate(jsonPredicate)
                .serdes(Serdes.String(), SerdeUtil.stockTransactionAggregationSerde());
    }

    public static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

}
