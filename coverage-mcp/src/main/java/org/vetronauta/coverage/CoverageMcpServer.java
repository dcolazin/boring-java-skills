package org.vetronauta.coverage;

import tools.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/**
 * Main entry point for the Coverage MCP Server.
 * Uses stdio transport for communication with MCP clients.
 */
public class CoverageMcpServer {

    private static final Logger LOG = LoggerFactory.getLogger(CoverageMcpServer.class);

    public static void main(String[] args) {
        // Ensure all logging goes to stderr so stdout remains clean for MCP protocol
        System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));

        LOG.info("Starting Coverage MCP Server...");

        // The SDK (2.0.1) stdio transport does not terminate the JVM when the
        // client closes stdin, and offers no close-notification callback. We
        // wrap stdin and observe EOF directly, letting the server shut down
        // gracefully when the client disconnects.
        CountDownLatch stdinEof = new CountDownLatch(1);
        InputStream wrappedStdin = new FilterInputStream(System.in) {
            private void onEof() {
                stdinEof.countDown();
            }

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b == -1) {
                    onEof();
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n == -1) {
                    onEof();
                }
                return n;
            }
        };

        try {
            // Create JSON mapper (Jackson 3)
            JsonMapper objectMapper = JsonMapper.builder().build();
            JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);

            // Create stdio transport on the EOF-observing stdin
            OutputStream stdout = System.out;
            StdioServerTransportProvider transport =
                    new StdioServerTransportProvider(jsonMapper, wrappedStdin, stdout);

            // Build server with tools registered up-front (required for the
            // tool capabilities flag; addTool() after build() throws)
            McpSyncServer server = McpServer.sync(transport)
                    .tools(McpTools.ANALYZE_COVERAGE, McpTools.LIST_CHANGED_FILES)
                    .build();

            // Graceful cleanup on SIGTERM/SIGINT (client process teardown)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (Exception e) {
                    LOG.debug("Error closing server on JVM shutdown: {}", e.getMessage());
                }
            }, "coverage-mcp-shutdown"));

            LOG.info("Server started, waiting for connections...");

            // Block until the client disconnects (stdin EOF), then exit cleanly
            stdinEof.await();
            LOG.info("Client disconnected (stdin EOF); shutting down.");
            server.close();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Server interrupted; shutting down.");
        } catch (Exception e) {
            LOG.error("Server error: ", e);
            // Belt and braces: even if the SLF4J binding were missing or
            // broken, a fatal startup failure must always be visible on stderr
            e.printStackTrace();
            System.exit(1);
        }
    }
}
