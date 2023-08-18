package io.confluent.developer.grpc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.confluent.developer.proto.InternalQueryGrpc;
import io.confluent.developer.proto.KeyQueryMetadataProto;
import io.confluent.developer.proto.KeyQueryRequestProto;
import io.confluent.developer.proto.MultKeyQueryRequestProto;
import io.confluent.developer.proto.QueryResponseProto;
import io.confluent.developer.proto.RangeQueryRequestProto;
import io.confluent.developer.query.FilteredRangeQuery;
import io.confluent.developer.query.MultiKeyQuery;
import io.confluent.developer.query.QueryUtils;
import io.confluent.developer.streams.SerdeUtil;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.query.KeyQuery;
import org.apache.kafka.streams.query.Query;
import org.apache.kafka.streams.query.QueryResult;
import org.apache.kafka.streams.query.StateQueryRequest;
import org.apache.kafka.streams.query.StateQueryResult;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ValueAndTimestamp;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@GRpcService
@Component
public class InternalQueryService extends InternalQueryGrpc.InternalQueryImplBase {

    private final KafkaStreams kafkaStreams;
    @Value("${store.name}")
    String storeName;
    // protected scope for testing
    Serde<String> stringSerde;
    // protected scope for testing
    Serde<ValueAndTimestamp<JsonNode>> valueAndTimestampSerde;

    @Autowired
    public InternalQueryService(KafkaStreams kafkaStreams) {
        this.kafkaStreams = kafkaStreams;
    }

    @PostConstruct
    public void init() {
        stringSerde = Serdes.String();
        valueAndTimestampSerde = SerdeUtil.valueAndTimestampSerde();
    }


    @Override
    public void keyQueryService(final KeyQueryRequestProto request,
                                        final StreamObserver<QueryResponseProto> responseObserver) {

        final KeyQuery<String, ValueAndTimestamp<JsonNode>> keyQuery = KeyQuery.withKey(request.getSymbol());
        final KeyQueryMetadataProto keyMetadata = request.getKeyQueryMetadata();
        final Set<Integer> partitionSet = Collections.singleton(keyMetadata.getPartition());
        final StateQueryResult<ValueAndTimestamp<JsonNode>> keyQueryResult = kafkaStreams.query(StateQueryRequest.inStore(storeName)
                .withQuery(keyQuery)
                .withPartitions(partitionSet));
        final QueryResult<ValueAndTimestamp<JsonNode>> queryResult = keyQueryResult.getOnlyPartitionResult();

        final QueryResponseProto.Builder repsonseBuilder = QueryResponseProto.newBuilder();
        JsonNode aggregation = queryResult.getResult().value();
        repsonseBuilder.addAllExecutionInfo(queryResult.getExecutionInfo());
        ((ObjectNode)aggregation).put("timestamp", queryResult.getResult().timestamp());
        repsonseBuilder.addJsonResults(aggregation.toString());
        responseObserver.onNext(repsonseBuilder.build());
        responseObserver.onCompleted();
   }

    @Override
    public void rangeQueryService(RangeQueryRequestProto request, StreamObserver<QueryResponseProto> responseObserver) {
        final Query<KeyValueIterator<String, ValueAndTimestamp<JsonNode>>> rangeQuery =
                QueryUtils.createRangeQuery(request.getLower(),request.getUpper(), request.getPredicate());

        final StateQueryResult<KeyValueIterator<String, ValueAndTimestamp<JsonNode>>> keyQueryResult = kafkaStreams.query(StateQueryRequest.inStore(storeName)
                .withQuery(rangeQuery));
        final Map<Integer,QueryResult<KeyValueIterator<String, ValueAndTimestamp<JsonNode>>>> allPartitionResults = keyQueryResult.getPartitionResults();

        final QueryResponseProto.Builder repsonseBuilder = QueryResponseProto.newBuilder();

        List<String> jsonResults = new ArrayList<>();
        allPartitionResults.forEach((k,v) -> {
            var keyValues = v.getResult();
            keyValues.forEachRemaining(kv -> {
                long timestamp = kv.value.timestamp();
                JsonNode node = kv.value.value();
                ((ObjectNode) node).put("timestamp", timestamp);
                jsonResults.add(node.toString());
            });
        });
        repsonseBuilder.addAllJsonResults(jsonResults);
        responseObserver.onNext(repsonseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void multiKeyQueryService(MultKeyQueryRequestProto request, StreamObserver<QueryResponseProto> responseObserver) {
        final MultiKeyQuery<String, ValueAndTimestamp<JsonNode>> multiKeyQuery = MultiKeyQuery.<String, ValueAndTimestamp<JsonNode>>withKeys(new HashSet<>(request.getSymbolsList()))
                .keySerde(Serdes.String())
                .valueSerde(SerdeUtil.valueAndTimestampSerde());
        final KeyQueryMetadataProto keyMetadata = request.getKeyQueryMetadata();
        final Set<Integer> partitionSet = Collections.singleton(keyMetadata.getPartition());
        final StateQueryResult<KeyValueIterator<String, ValueAndTimestamp<JsonNode>>> keyQueryResult = kafkaStreams.query(StateQueryRequest.inStore(storeName)
                .withQuery(multiKeyQuery)
                .withPartitions(partitionSet));
        final QueryResult<KeyValueIterator<String, ValueAndTimestamp<JsonNode>>> queryResult = keyQueryResult.getOnlyPartitionResult();

        KeyValueIterator<String, ValueAndTimestamp<JsonNode>> aggregations = queryResult.getResult();
        List<String> jsonResults = new ArrayList<>();
        aggregations.forEachRemaining(kv -> {
            JsonNode node = kv.value.value();
            long timestamp = kv.value.timestamp();
            ((ObjectNode)node).put("timestamp", timestamp);
            jsonResults.add(node.toString());
        });
        responseObserver.onNext(QueryResponseProto.newBuilder().addAllJsonResults(jsonResults).build());
        responseObserver.onCompleted();
    }
}
