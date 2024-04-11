package ru.vk.itmo.test.bandurinvladislav;

import one.nio.http.HttpClient;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.bandurinvladislav.config.DhtServerConfig;
import ru.vk.itmo.test.bandurinvladislav.dao.BaseEntry;
import ru.vk.itmo.test.bandurinvladislav.dao.Config;
import ru.vk.itmo.test.bandurinvladislav.dao.Entry;
import ru.vk.itmo.test.bandurinvladislav.dao.ReferenceDao;
import ru.vk.itmo.test.bandurinvladislav.util.Constants;
import ru.vk.itmo.test.bandurinvladislav.util.MemSegUtil;
import ru.vk.itmo.test.bandurinvladislav.util.NetworkUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Server extends HttpServer {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final Map<String, HttpClient> clients;
    private final DaoWorkerPool workerPool;
    private final DhtServerConfig serverConfig;

    private final ReferenceDao dao;

    public Server(DhtServerConfig config, java.nio.file.Path workingDir) throws IOException {
        super(config);
        workerPool = new DaoWorkerPool(
                Constants.THREADS,
                Constants.THREADS,
                60,
                TimeUnit.SECONDS
        );

        serverConfig = config;
        workerPool.prestartAllCoreThreads();
        Config daoConfig = new Config(workingDir, Constants.FLUSH_THRESHOLD_BYTES);
        dao = new ReferenceDao(daoConfig);
        clients = new HashMap<>();
        config.clusterUrls.stream()
                .filter(url -> !url.equals(config.selfUrl))
                .forEach(u -> {
                    HttpClient client = new HttpClient(new ConnectionString(u));
                    client.setTimeout(100);
                    clients.put(u, client);
                });
        logger.info("Server started");
    }

    public Response getEntity(@Param(value = "id", required = true) String id) {
        Entry<MemorySegment> result = dao.get(MemSegUtil.fromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        Response response;
        if (result.value() == null) {
            response = new Response(Response.NOT_FOUND, Response.EMPTY);
        } else {
            response = Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
        }
        response.addHeader(Constants.HEADER_TIMESTAMP + result.timestamp());
        return response;
    }

    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        MemorySegment.ofArray(request.getBody()),
                        System.currentTimeMillis()
                )
        );
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        null,
                        System.currentTimeMillis()
                )
        );
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            workerPool.execute(() -> {
                try {
                    handleDaoCall(request, session);
                } catch (IOException e) {
                    logger.error("IO exception during request handling: " + e.getMessage());
                    try {
                        session.sendError(Response.INTERNAL_ERROR, null);
                    } catch (IOException ex) {
                        logger.error("Exception while sending close connection:", e);
                        session.scheduleClose();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            logger.info("queue is full");
            if (workerPool.isShutdown()) {
                session.sendError(Response.SERVICE_UNAVAILABLE, null);
                return;
            }
            session.sendError(Constants.TOO_MANY_REQUESTS, null);
        }
    }

    @SuppressWarnings("unused")
    private void handleDaoCall(Request request, HttpSession session) throws IOException {
        String path = request.getPath();
        if (!path.equals(Constants.ENDPOINT)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        if (NetworkUtil.isMethodNotAllowed(request)) {
            session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
            return;
        }

        int ack = NetworkUtil.getParameterAsInt(request.getParameter(Constants.PARAMETER_ACK),
                serverConfig.clusterUrls.size() / 2 + 1);
        int from = NetworkUtil.getParameterAsInt(
                request.getParameter(Constants.PARAMETER_FROM), serverConfig.clusterUrls.size());
        String key = request.getParameter(Constants.PARAMETER_ID);

        Response validationResponse = NetworkUtil.validateParams(key, ack, from, serverConfig.clusterUrls.size());
        if (validationResponse != null) {
            session.sendResponse(validationResponse);
            return;
        }

        String sender = request.getHeader(Constants.HEADER_SENDER);
        if (sender != null) {
            session.sendResponse(invokeLocal(request, key));
            return;
        } else {
            request.addHeader(Constants.HEADER_SENDER + serverConfig.clusterUrls);
        }

        List<HttpClient> sortedClients = getClientsByKey(key, from);
        RequestProcessingState rs = new RequestProcessingState(from);
        for (HttpClient client : sortedClients) {
            if (client == null) {
                Response response = invokeLocal(request, key);
                NetworkUtil.handleResponse(session, rs, response, ack, from);
            } else {
                CompletableFuture<Response> remote = CompletableFuture.supplyAsync(
                        () -> invokeRemote(request, client), workerPool
                ).completeOnTimeout(
                        new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY),
                        100, TimeUnit.MILLISECONDS
                );
                CompletableFuture<Void> responseAction = remote.thenAccept(r -> {
                    if (r.getStatus() == 500 || r.getStatus() == 504) {
                        NetworkUtil.trySendResponse(session, new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
                    } else {
                        NetworkUtil.handleResponse(session, rs, r, ack, from);
                    }
                });
            }
        }
    }

    private Response invokeLocal(Request request, String key) {
        Response response;
        try {
            response = switch (request.getMethod()) {
                case Request.METHOD_GET -> getEntity(key);
                case Request.METHOD_PUT -> putEntity(key, request);
                case Request.METHOD_DELETE -> deleteEntity(key);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (Exception e) {
            logger.error("Internal error during request handling: " + e.getMessage());
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }

        return response;
    }

    private Response invokeRemote(Request request, HttpClient client) {
        Response response;
        try {
            response = switch (request.getMethod()) {
                case Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE -> client.invoke(request);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (Exception e) {
            logger.error("Internal error during request handling: " + e.getMessage());
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return response;
    }

    private List<HttpClient> getClientsByKey(String key, int from) {
        TreeMap<Integer, String> hashUrlSorted = new TreeMap<>();

        for (int i = 0; i < serverConfig.clusterUrls.size(); i++) {
            String currentNodeUrl = serverConfig.clusterUrls.get(i);
            int currentHash = Hash.murmur3(currentNodeUrl + key);
            hashUrlSorted.put(currentHash, currentNodeUrl);
        }

        return hashUrlSorted
                .values()
                .stream()
                .limit(from)
                .map(clients::get)
                .toList();
    }

    @Override
    public synchronized void stop() {
        super.stop();

        workerPool.gracefulShutdown();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
