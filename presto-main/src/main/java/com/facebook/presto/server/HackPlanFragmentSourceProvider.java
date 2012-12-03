/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.server;

import com.facebook.presto.metadata.ColumnHandle;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.split.DataStreamProvider;
import com.facebook.presto.sql.planner.PlanFragmentSource;
import com.facebook.presto.sql.planner.PlanFragmentSourceProvider;
import com.google.common.base.Function;
import io.airlift.http.client.ApacheHttpClient;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpClientConfig;
import io.airlift.json.JsonCodec;
import io.airlift.units.Duration;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.util.Threads.threadsNamed;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.transform;

@Immutable
public class HackPlanFragmentSourceProvider
        implements PlanFragmentSourceProvider
{
    private final DataStreamProvider dataStreamProvider;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final JsonCodec<QueryTaskInfo> queryTaskInfoCodec;
    private final int pageBufferMax;

    @Inject
    public HackPlanFragmentSourceProvider(DataStreamProvider dataStreamProvider, JsonCodec<QueryTaskInfo> queryTaskInfoCodec)
    {
        this.dataStreamProvider = checkNotNull(dataStreamProvider, "dataStreamProvider is null");
        this.queryTaskInfoCodec = checkNotNull(queryTaskInfoCodec, "queryTaskInfoCodec is null");

        int processors = Runtime.getRuntime().availableProcessors();
        executor = new ThreadPoolExecutor(processors,
                processors,
                1, TimeUnit.MINUTES,
                new SynchronousQueue<Runnable>(),
                threadsNamed("shard-query-processor-%d"),
                new ThreadPoolExecutor.CallerRunsPolicy());


        httpClient = new ApacheHttpClient(new HttpClientConfig()
                .setConnectTimeout(new Duration(5, TimeUnit.SECONDS))
                .setReadTimeout(new Duration(5, TimeUnit.SECONDS)));

        this.pageBufferMax = 10;
    }

    @Override
    public Operator createDataStream(PlanFragmentSource source, List<ColumnHandle> columns)
    {
        checkNotNull(source, "source is null");
        if (source instanceof ExchangePlanFragmentSource) {
            final ExchangePlanFragmentSource exchangeSource = (ExchangePlanFragmentSource) source;
            return new QueryDriversOperator(pageBufferMax, transform(exchangeSource.getSources().entrySet(), new Function<Entry<String, URI>, QueryDriverProvider>() {
                @Override
                public QueryDriverProvider apply(Entry<String, URI> source)
                {
                    return new HttpTaskClient(
                            source.getKey(),
                            source.getValue(),
                            exchangeSource.getOutputId(),
                            exchangeSource.getTupleInfos(),
                            httpClient,
                            executor,
                            queryTaskInfoCodec
                    );
                }
            }));
        }
        else if (source instanceof TableScanPlanFragmentSource) {
            TableScanPlanFragmentSource tableScanSource = (TableScanPlanFragmentSource) source;
            return dataStreamProvider.createDataStream(tableScanSource.getSplit(), columns);
        }

        throw new IllegalArgumentException("Unsupported source type " + source.getClass().getName());
    }
}