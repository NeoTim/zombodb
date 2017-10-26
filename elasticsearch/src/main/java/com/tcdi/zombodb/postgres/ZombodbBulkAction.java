package com.tcdi.zombodb.postgres;

import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.rest.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestStatus.OK;

public class ZombodbBulkAction extends BaseRestHandler {

    private ClusterService clusterService;

    @Inject
    public ZombodbBulkAction(Settings settings, RestController controller, Client client, ClusterService clusterService) {
        super(settings, controller, client);

        this.clusterService = clusterService;

        controller.registerHandler(POST, "/{index}/{type}/_zdbbulk", this);
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, final Client client) throws Exception {
        BulkRequest bulkRequest = Requests.bulkRequest();
        BulkResponse response;

        String defaultIndex = request.param("index");
        String defaultType = request.param("type");
        String defaultRouting = request.param("routing");
        boolean refresh = request.paramAsBoolean("refresh", false);
        boolean isdelete = false;
        int requestNumber = request.paramAsInt("request_no", -1);

        String consistencyLevel = request.param("consistency");
        if (consistencyLevel != null) {
            bulkRequest.consistencyLevel(WriteConsistencyLevel.fromString(consistencyLevel));
        }

        bulkRequest.listenerThreaded(false);
        bulkRequest.timeout(request.paramAsTime("timeout", BulkShardRequest.DEFAULT_TIMEOUT));
        bulkRequest.refresh(refresh);
        bulkRequest.add(request.content(), defaultIndex, defaultType, defaultRouting, null, true);

        List<ActionRequest> xmaxRequests = new ArrayList<>();
        List<ActionRequest> abortedRequests = new ArrayList<>();
        if (!bulkRequest.requests().isEmpty()) {
            isdelete = bulkRequest.requests().get(0) instanceof DeleteRequest;

            if (isdelete) {
                handleDeleteRequests(client, bulkRequest.requests(), defaultIndex, xmaxRequests);
            } else {
                handleIndexRequests(client, bulkRequest.requests(), defaultIndex, requestNumber, xmaxRequests, abortedRequests);
            }
        }


        if (isdelete) {
            // when deleting, we need to delete the "data" docs first
            // otherwise VisibilityQueryHelper might think "data" docs don't have an "xmax" when they really do
            response = client.bulk(bulkRequest).actionGet();

            if (!response.hasFailures()) {
                // then we can delete from "xmax"
                response = processTrackingRequests(request, client, xmaxRequests);
            }
        } else {
            // when inserting, we first need to add the "aborted" docs
            response = processTrackingRequests(request, client, abortedRequests);

            if (!response.hasFailures()) {
                // then we need to add the "xmax" docs
                // otherwise VisibilityQueryHelper might think "data" docs don't have an "xmax" when they really do
                response = processTrackingRequests(request, client, xmaxRequests);

                if (!response.hasFailures()) {
                    // then we can insert into "data"
                    response = client.bulk(bulkRequest).actionGet();
                }
            }
        }

        channel.sendResponse(buildResponse(response, JsonXContent.contentBuilder()));
    }

    private BulkResponse processTrackingRequests(RestRequest request, Client client, List<ActionRequest> trackingRequests) {
        if (trackingRequests.isEmpty())
            return new BulkResponse(new BulkItemResponse[0], 0);

        BulkRequest bulkRequest;
        bulkRequest = Requests.bulkRequest();
        bulkRequest.timeout(request.paramAsTime("timeout", BulkShardRequest.DEFAULT_TIMEOUT));
        bulkRequest.refresh(request.paramAsBoolean("refresh", false));
        bulkRequest.requests().addAll(trackingRequests);
        return client.bulk(bulkRequest).actionGet();
    }

    static RestResponse buildResponse(BulkResponse response, XContentBuilder builder) throws Exception {
        int errorCnt = 0;
        builder.startObject();
        if (response.hasFailures()) {
            builder.startArray(Fields.ITEMS);
            main_loop: for (BulkItemResponse itemResponse : response) {
                if (itemResponse.isFailed()) {

                    // handle failure conditions that we know are
                    // okay/expected as if they never happened
                    BulkItemResponse.Failure failure = itemResponse.getFailure();

                    switch (failure.getStatus()) {
                        case CONFLICT:
                            if (failure.getMessage().contains("VersionConflictEngineException")) {
                                if ("xmax".equals(itemResponse.getType())) {
                                    if ("delete".equals(itemResponse.getOpType())) {
                                        // this is a version conflict error where we tried to delete
                                        // an old xmax doc, which is perfectly acceptable
                                        continue main_loop;
                                    }
                                }
                            }
                            break;

                        default:
                            errorCnt++;
                            break;
                    }

                    builder.startObject();
                    builder.startObject(itemResponse.getOpType());
                    builder.field(Fields._INDEX, itemResponse.getIndex());
                    builder.field(Fields._TYPE, itemResponse.getType());
                    builder.field(Fields._ID, itemResponse.getId());
                    long version = itemResponse.getVersion();
                    if (version != -1) {
                        builder.field(Fields._VERSION, itemResponse.getVersion());
                    }
                    if (itemResponse.isFailed()) {
                        builder.field(Fields.STATUS, itemResponse.getFailure().getStatus().getStatus());
                        builder.field(Fields.ERROR, itemResponse.getFailure().getMessage());
                    } else {
                        if (itemResponse.getResponse() instanceof DeleteResponse) {
                            DeleteResponse deleteResponse = itemResponse.getResponse();
                            if (deleteResponse.isFound()) {
                                builder.field(Fields.STATUS, RestStatus.OK.getStatus());
                            } else {
                                builder.field(Fields.STATUS, RestStatus.NOT_FOUND.getStatus());
                            }
                            builder.field(Fields.FOUND, deleteResponse.isFound());
                        } else if (itemResponse.getResponse() instanceof IndexResponse) {
                            IndexResponse indexResponse = itemResponse.getResponse();
                            if (indexResponse.isCreated()) {
                                builder.field(Fields.STATUS, RestStatus.CREATED.getStatus());
                            } else {
                                builder.field(Fields.STATUS, RestStatus.OK.getStatus());
                            }
                        } else if (itemResponse.getResponse() instanceof UpdateResponse) {
                            UpdateResponse updateResponse = itemResponse.getResponse();
                            if (updateResponse.isCreated()) {
                                builder.field(Fields.STATUS, RestStatus.CREATED.getStatus());
                            } else {
                                builder.field(Fields.STATUS, RestStatus.OK.getStatus());
                            }
                        }
                    }
                    builder.endObject();
                    builder.endObject();
                }
            }
            builder.endArray();
            builder.field(Fields.TOOK, response.getTookInMillis());
            if (errorCnt > 0) {
                builder.field(Fields.ERRORS, true);
            }
        }
        builder.endObject();

        return new BytesRestResponse(OK, builder);
    }

    private void handleDeleteRequests(Client client, List<ActionRequest> requests, String defaultIndex, List<ActionRequest> xmaxRequests) {

        for (ActionRequest ar : requests) {
            DeleteRequest doc = (DeleteRequest) ar;

            xmaxRequests.add(
                    new DeleteRequestBuilder(client)
                            .setIndex(defaultIndex)
                            .setType("xmax")
                            .setRouting(doc.id())
                            .setId(doc.id())
                            .request()
            );

            doc.routing(doc.id());
        }
    }

    private void handleIndexRequests(Client client, List<ActionRequest> requests, String defaultIndex, int requestNumber, List<ActionRequest> xmaxRequests, List<ActionRequest> abortedRequests) {

        int cnt = 0;
        for (ActionRequest ar : requests) {
            IndexRequest doc = (IndexRequest) ar;
            Map<String, Object> data = doc.sourceAsMap();
            String prev_ctid = (String) data.get("_prev_ctid");
            Number xmin = (Number) data.get("_xmin");
            Number cmin = (Number) data.get("_cmin");
            Number sequence = (Number) data.get("_zdb_seq");    // -1 means an index build (CREATE INDEX)

            {
                // encode a few things into one binary field
                // and then add that field to the json source of this document
                String[] parts = doc.id().split("-");
                int blockno = Integer.parseInt(parts[0]);
                int offno = Integer.parseInt(parts[1]);
                BytesRef encodedTuple = encodeTuple(xmin.longValue(), cmin.intValue(), blockno, offno);

                // re-create the source as a byte array.
                // this is much faster than using doc.source(Map<String, Object>)
                byte[] source = doc.source().toBytes();
                int start = 0;
                int len = source.length;

                // backup to the last closing bracket
                while (source[start + --len] != '}') ;

                byte[] valueBytes = (",\"_zdb_encoded_tuple\":\"" + Base64.encodeBytes(encodedTuple.bytes) + "\"}").getBytes();
                byte[] dest = new byte[len + valueBytes.length];
                System.arraycopy(source, start, dest, 0, len);
                System.arraycopy(valueBytes, 0, dest, len, valueBytes.length);
                doc.source(dest);
            }

            if (prev_ctid != null) {
                // we are inserting a new doc that replaces a previous doc (an UPDATE)
                String[] parts = prev_ctid.split("-");
                int blockno = Integer.parseInt(parts[0]);
                int offno = Integer.parseInt(parts[1]);
                BytesRef encodedTuple = encodeTuple(xmin.longValue(), cmin.intValue(), blockno, offno);

                xmaxRequests.add(
                        new IndexRequestBuilder(client)
                                .setIndex(defaultIndex)
                                .setType("xmax")
                                .setVersionType(VersionType.FORCE)
                                .setVersion(xmin.longValue())
                                .setRouting(prev_ctid)
                                .setId(prev_ctid)
                                .setSource("_xmax", xmin, "_cmax", cmin, "_replacement_ctid", doc.id(), "_zdb_encoded_tuple", encodedTuple, "_zdb_reason", "U")
                                .request()
                );
            }

            if (sequence.intValue() > -1) {
                // delete a possible existing xmax value for this doc
                // but only if we're in an index build (ie, CREATE INDEX)
                xmaxRequests.add(
                        new DeleteRequestBuilder(client)
                                .setIndex(defaultIndex)
                                .setType("xmax")
                                .setRouting(doc.id())
                                .setId(doc.id())
                                .request()
                );
            }

            // only add the "aborted" xid entry if this is the first
            // record in what might be a batch of inserts from one statement
            if (requestNumber == 0 && cnt == 0 && sequence.intValue() > -1) {
                GetSettingsResponse indexSettings = client.admin().indices().getSettings(client.admin().indices().prepareGetSettings(defaultIndex).request()).actionGet();
                int shards = Integer.parseInt(indexSettings.getSetting(defaultIndex, "index.number_of_shards"));
                String[] routingTable = RoutingHelper.getRoutingTable(client, clusterService, defaultIndex, shards);

                for (String routing : routingTable) {
                    abortedRequests.add(
                            new IndexRequestBuilder(client)
                                    .setIndex(defaultIndex)
                                    .setType("aborted")
                                    .setRouting(routing)
                                    .setId(String.valueOf(xmin))
                                    .setSource("_zdb_xid", xmin)
                                    .request()
                    );
                }
            }

            // every doc with an "_id" that is a ctid needs a version
            // and that version must be *larger* than the document that might
            // have previously occupied this "_id" value -- the Postgres transaction id (xid)
            // works just fine for this as it's always increasing
            doc.opType(IndexRequest.OpType.CREATE);
            doc.version(xmin.longValue());
            doc.versionType(VersionType.FORCE);
            doc.routing(doc.id());

            cnt++;
        }
    }

    static BytesRef encodeTuple(long xid, int cmin, int blockno, int offno) {
        try {
            byte[] tuple = new byte[4 + 2 + 8 + 4];  // blockno + offno + xmax + cmax
            ByteArrayDataOutput out = new ByteArrayDataOutput(tuple);
            out.writeVInt(blockno);
            out.writeVInt(offno);
            out.writeVLong(xid);
            out.writeVInt(cmin);
            return new BytesRef(tuple, 0, out.getPosition());
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    private static final class Fields {
        static final XContentBuilderString ITEMS = new XContentBuilderString("items");
        static final XContentBuilderString ERRORS = new XContentBuilderString("errors");
        static final XContentBuilderString _INDEX = new XContentBuilderString("_index");
        static final XContentBuilderString _TYPE = new XContentBuilderString("_type");
        static final XContentBuilderString _ID = new XContentBuilderString("_id");
        static final XContentBuilderString STATUS = new XContentBuilderString("status");
        static final XContentBuilderString ERROR = new XContentBuilderString("error");
        static final XContentBuilderString TOOK = new XContentBuilderString("took");
        static final XContentBuilderString _VERSION = new XContentBuilderString("_version");
        static final XContentBuilderString FOUND = new XContentBuilderString("found");
    }

}