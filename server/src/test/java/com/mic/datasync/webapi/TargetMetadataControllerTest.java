package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.endpoint.AgentClient;
import com.mic.datasync.endpoint.AgentProtocol;
import com.mic.datasync.endpoint.EndpointRecord;
import com.mic.datasync.endpoint.EndpointService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetMetadataControllerTest {

    private final DatabaseConfigService configService = mock(DatabaseConfigService.class);
    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    private final DatabaseAdapterFactory adapterFactory = mock(DatabaseAdapterFactory.class);
    private final EndpointService endpointService = mock(EndpointService.class);
    private final AgentClient agentClient = mock(AgentClient.class);

    private final TargetMetadataController controller = new TargetMetadataController(
            configService, connectionFactory, adapterFactory, endpointService, agentClient);

    private DatabaseConfig remoteConfig(String id) {
        return new DatabaseConfig(
                id, "sink-remote", "远程目标库", DatabaseRole.SINK, DatabaseType.OPEN_GAUSS,
                "jdbc:opengauss://db:5432/sync", "mic_prod", "remote-catalog",
                "opengauss", null, null);
    }

    private EndpointRecord remoteEndpoint() {
        return new EndpointRecord(
                "sink-remote", "远程 Sink", DatabaseRole.SINK,
                "http://sink:19090/mic-data-sync", "sink-instance", "sink-token",
                false, "READY", null, null, null);
    }

    private DatabaseConfig selfConfig(String id) {
        return new DatabaseConfig(
                id, "self-sink", "本地目标库", DatabaseRole.SINK, DatabaseType.OPEN_GAUSS,
                "jdbc:opengauss://127.0.0.1:15432/sync", "mic_prod", "real-password",
                "opengauss", null, null);
    }

    private EndpointRecord selfEndpoint() {
        return new EndpointRecord(
                "self-sink", "本地 Sink", DatabaseRole.SINK,
                "http://127.0.0.1:19090/mic-data-sync", "self-instance", "self-token",
                true, "READY", null, null, null);
    }

    @Test
    void remoteSinkSchemasAreForwardedThroughAgentWithoutLocalConnection() throws Exception {
        when(configService.get("target-remote")).thenReturn(Optional.of(remoteConfig("target-remote")));
        when(endpointService.get("sink-remote")).thenReturn(Optional.of(remoteEndpoint()));
        when(agentClient.listTargetSchemas(remoteEndpoint(), "target-remote"))
                .thenReturn(List.of("mic_sync", "public"));

        ResponseEntity<?> response = controller.listSchemas("target-remote");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).get("schemas"))
                .isEqualTo(List.of("mic_sync", "public"));
        verify(agentClient).listTargetSchemas(remoteEndpoint(), "target-remote");
        verify(connectionFactory, never()).open(any());
    }

    @Test
    void selfSinkSchemasRunLocallyWithoutAgentCall() throws Exception {
        when(configService.get("target-self")).thenReturn(Optional.of(selfConfig("target-self")));
        when(endpointService.get("self-sink")).thenReturn(Optional.of(selfEndpoint()));
        Connection connection = mock(Connection.class);
        TargetDatabaseAdapter adapter = mock(TargetDatabaseAdapter.class);
        when(connectionFactory.open(selfConfig("target-self"))).thenReturn(connection);
        when(adapterFactory.targetAdapter(DatabaseType.OPEN_GAUSS)).thenReturn(adapter);
        when(adapter.listSchemas(connection)).thenReturn(List.of("mic_sync"));

        ResponseEntity<?> response = controller.listSchemas("target-self");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).get("schemas")).isEqualTo(List.of("mic_sync"));
        verify(agentClient, never()).listTargetSchemas(any(), any());
    }

    @Test
    void remoteTableMetadataIsForwardedAndMappedToConsoleResponse() {
        when(configService.get("target-remote")).thenReturn(Optional.of(remoteConfig("target-remote")));
        when(endpointService.get("sink-remote")).thenReturn(Optional.of(remoteEndpoint()));
        when(agentClient.readTargetTableMetadata(remoteEndpoint(), "mic_sync", "patient", "target-remote"))
                .thenReturn(new AgentProtocol.TargetTableMetadata(
                        "mic_sync", "patient",
                        List.of(new AgentProtocol.TargetColumn("id", "int8", false, true)),
                        List.of("id"),
                        List.of()));

        ResponseEntity<?> response = controller.tableMetadata("mic_sync", "patient", "target-remote");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TargetMetadataController.TargetMetadataResponse body =
                (TargetMetadataController.TargetMetadataResponse) response.getBody();
        assertThat(body.schema()).isEqualTo("mic_sync");
        assertThat(body.table()).isEqualTo("patient");
        assertThat(body.columns()).hasSize(1);
        assertThat(body.columns().getFirst().name()).isEqualTo("id");
        assertThat(body.columns().getFirst().primaryKey()).isTrue();
        verify(agentClient).readTargetTableMetadata(remoteEndpoint(), "mic_sync", "patient", "target-remote");
    }
}
