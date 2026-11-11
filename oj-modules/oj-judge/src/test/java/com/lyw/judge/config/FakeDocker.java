package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 单元测试用可控 Docker 替身，不能作为真实 Docker 验收证据。 */
final class FakeDocker {
    static final String IMAGE = "sha256:test-image";
    final DockerClient control = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    final Map<String, Box> containers = new ConcurrentHashMap<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final AtomicInteger creates = new AtomicInteger();
    final AtomicInteger removes = new AtomicInteger();
    final AtomicInteger clientsClosed = new AtomicInteger();
    final AtomicInteger streamsClosed = new AtomicInteger();
    final Map<String, Execution> executions = new ConcurrentHashMap<>();
    volatile boolean failRemoval;
    volatile boolean leaveAfterRemoval;
    volatile boolean badLimits;
    volatile boolean failStart;
    volatile boolean loseCreateReply;
    volatile boolean createNeverMaterializes;
    volatile boolean failBeforeCreate;
    volatile int failCreateAfter = Integer.MAX_VALUE;
    volatile Runnable beforeRemove;
    volatile Consumer<String> beforeRemoveId;
    volatile Consumer<Execution> onExecution;
    private final AtomicInteger execSequence = new AtomicInteger();

    FakeDocker() {
        when(control.inspectImageCmd(anyString()).exec().getId()).thenReturn(IMAGE);
        when(control.listContainersCmd().withShowAll(true).exec()).thenAnswer(invocation -> containers.values().stream().map(box -> {
            Container container = mock(Container.class);
            when(container.getId()).thenReturn(box.id);
            when(container.getNames()).thenReturn(new String[]{"/" + box.name});
            return container;
        }).toList());
        when(control.createContainerCmd(anyString())).thenAnswer(invocation -> createCommand());
        when(control.startContainerCmd(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            StartContainerCmd command = mock(StartContainerCmd.class, RETURNS_SELF);
            when(command.exec()).thenAnswer(ignored -> {
                if (failStart) throw new IllegalStateException("注入启动失败");
                box(id).running = true;
                return null;
            });
            return command;
        });
        when(control.inspectContainerCmd(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            InspectContainerCmd command = mock(InspectContainerCmd.class, RETURNS_SELF);
            when(command.exec()).thenAnswer(ignored -> inspection(box(id)));
            return command;
        });
        when(control.removeContainerCmd(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            RemoveContainerCmd command = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(command.exec()).thenAnswer(ignored -> {
                if (beforeRemove != null) beforeRemove.run();
                if (beforeRemoveId != null) beforeRemoveId.accept(id);
                removes.incrementAndGet();
                if (failRemoval) throw new IllegalStateException("注入删除失败");
                if (!leaveAfterRemoval) containers.remove(id);
                events.add("remove:" + id);
                return null;
            });
            return command;
        });
        when(control.execCreateCmd(anyString())).thenAnswer(invocation -> {
            String container = invocation.getArgument(0);
            AtomicReference<String[]> words = new AtomicReference<>();
            ExecCreateCmd command = mock(ExecCreateCmd.class, call -> {
                if ("withCmd".equals(call.getMethod().getName())) {
                    words.set((String[]) call.getRawArguments()[0]);
                    return call.getMock();
                }
                return RETURNS_SELF.answer(call);
            });
            when(command.exec()).thenAnswer(ignored -> {
                String id = "exec-" + execSequence.incrementAndGet();
                executions.put(id, new Execution(id, container, words.get()));
                ExecCreateCmdResponse response = mock(ExecCreateCmdResponse.class);
                when(response.getId()).thenReturn(id);
                return response;
            });
            return command;
        });
        when(control.inspectExecCmd(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            InspectExecCmd command = mock(InspectExecCmd.class, RETURNS_SELF);
            when(command.exec()).thenAnswer(ignored -> {
                Execution execution = executions.get(id);
                InspectExecResponse response = mock(InspectExecResponse.class);
                when(response.isRunning()).thenReturn(execution.running);
                when(response.getExitCodeLong()).thenReturn(execution.exitCode);
                return response;
            });
            return command;
        });
    }

    private CreateContainerCmd createCommand() {
        if (failBeforeCreate) throw new IllegalStateException("注入发送前失败");
        CreateContainerCmd command = mock(CreateContainerCmd.class, RETURNS_SELF);
        AtomicReference<String> name = new AtomicReference<>();
        AtomicReference<HostConfig> config = new AtomicReference<>();
        AtomicReference<Map<String, String>> labels = new AtomicReference<>();
        doAnswer(call -> { name.set(call.getArgument(0)); return command; }).when(command).withName(anyString());
        doAnswer(call -> { config.set(call.getArgument(0)); return command; }).when(command).withHostConfig(any(HostConfig.class));
        doAnswer(call -> { labels.set(call.getArgument(0)); return command; }).when(command).withLabels(anyMap());
        when(command.exec()).thenAnswer(ignored -> {
            int sequence = creates.incrementAndGet();
            if (sequence > failCreateAfter || createNeverMaterializes) throw new IllegalStateException("创建结果未知");
            String id = "container-" + sequence;
            HostConfig hostConfig = config.get();
            if (badLimits) hostConfig.withPidsLimit(0L);
            Box box = new Box(id, name.get(), hostConfig, labels.get());
            containers.put(id, box);
            events.add("create:" + id);
            if (loseCreateReply) throw new IllegalStateException("创建已完成但响应丢失");
            CreateContainerResponse response = new CreateContainerResponse();
            response.setId(id);
            return response;
        });
        return command;
    }

    DockerClient streamClient() throws Exception {
        DockerClient client = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        doAnswer(ignored -> { clientsClosed.incrementAndGet(); return null; }).when(client).close();
        when(client.execStartCmd(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            ExecStartCmd command = mock(ExecStartCmd.class, RETURNS_SELF);
            doAnswer(call -> {
                ResultCallback<Frame> callback = call.getArgument(0);
                Execution execution = executions.get(id);
                execution.callback = callback;
                callback.onStart(streamsClosed::incrementAndGet);
                if ("cat".equals(execution.words[0])) {
                    Box box = box(execution.container);
                    Path path = box.directory.resolve(Path.of(execution.words[1]).getFileName());
                    callback.onNext(new Frame(StreamType.STDOUT, Files.readAllBytes(path)));
                    execution.finish(0L);
                } else if (onExecution != null) {
                    onExecution.accept(execution);
                } else {
                    execution.finish(0L);
                }
                return callback;
            }).when(command).exec(any());
            return command;
        });
        when(client.statsCmd(anyString())).thenAnswer(invocation -> {
            StatsCmd command = mock(StatsCmd.class, RETURNS_SELF);
            doAnswer(call -> {
                ResultCallback<Statistics> callback = call.getArgument(0);
                callback.onStart(streamsClosed::incrementAndGet);
                return callback;
            }).when(command).exec(any());
            return command;
        });
        return client;
    }

    SandboxStreamClientFactory factory() {
        return () -> {
            try { return streamClient(); } catch (Exception error) { throw new IllegalStateException(error); }
        };
    }

    private Box box(String idOrName) {
        Box box = containers.get(idOrName);
        if (box == null) box = containers.values().stream().filter(item -> item.name.equals(idOrName)).findFirst().orElse(null);
        if (box == null) throw new NotFoundException("未找到 " + idOrName);
        return box;
    }

    private InspectContainerResponse inspection(Box box) {
        InspectContainerResponse response = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(response.getId()).thenReturn(box.id);
        when(response.getImageId()).thenReturn(IMAGE);
        when(response.getConfig().getLabels()).thenReturn(box.labels);
        when(response.getState().getRunning()).thenReturn(box.running);
        when(response.getHostConfig()).thenReturn(box.config);
        when(response.getMounts()).thenReturn(List.of(new InspectContainerResponse.Mount()
                .withSource(box.directory.toString()).withDestination(new Volume("/usr/share/java")).withRw(true)));
        return response;
    }

    static final class Box {
        final String id;
        final String name;
        final HostConfig config;
        final Map<String, String> labels;
        final Path directory;
        volatile boolean running;
        Box(String id, String name, HostConfig config, Map<String, String> labels) {
            this.id = id; this.name = name; this.config = config; this.labels = Map.copyOf(labels);
            this.directory = Path.of(config.getBinds()[0].getPath());
        }
    }

    static final class Execution {
        final String id;
        final String container;
        final String[] words;
        volatile Long exitCode;
        volatile boolean running = true;
        ResultCallback<Frame> callback;
        Execution(String id, String container, String[] words) { this.id = id; this.container = container; this.words = words; }
        void finish(Long code) {
            exitCode = code; running = false; callback.onComplete();
        }
    }
}
