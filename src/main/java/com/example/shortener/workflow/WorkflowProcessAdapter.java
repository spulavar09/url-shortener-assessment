package com.example.shortener.workflow;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import static com.example.shortener.workflow.WorkflowModels.*;
@Component
public class WorkflowProcessAdapter {
    private final WorkflowProperties config;
    private final WorkspaceStore files;
    private final WorkflowService service;
    private final ObjectMapper json;
    public WorkflowProcessAdapter(WorkflowProperties config,WorkspaceStore files,WorkflowService service,ObjectMapper json) {
        this.config=config;
        this.files=files;
        this.service=service;
        this.json=json;
    }
    public Outcome execute(Claim claim) {
        Path workspace=files.owned(claim.workspace());
        Path capture=workspace.getParent().resolve(claim.attemptId()+".capture");
        Path result=workspace.getParent().resolve(claim.attemptId()+".result.json");
        boolean verify=claim.task().type().equals("VERIFY"),write=claim.task().skill()!=null&&claim.task().skill().equals("shortener-implementation-repair");
        Process process=null;
        long started=System.nanoTime();
        try {
            List<String> command;
            if(verify) {
                command=new ArrayList<>(List.of(config.mavenCommand(),"--batch-mode","--no-transfer-progress","-DfailIfNoTests=true","-Dmaven.test.skip=false","-DskipTests=false"));
                if(config.verificationOffline())command.add("--offline");
                if(!config.mavenRepository().isBlank())command.add("-Dmaven.repo.local="+Path.of(config.mavenRepository()).toAbsolutePath().normalize());
                command.add("verify");
            }
            else command=new ArrayList<>(List.of(config.codexCommand(),"exec","--sandbox",write?"workspace-write":"read-only","--skip-git-repo-check","--ephemeral","--json","--color","never","-c","shell_environment_policy.inherit=\"none\"","--output-last-message",result.toString(),"-"));
            if(!verify) {
                Path schema=workspace.resolve("approved-inputs/result-schema.json");
                Files.createDirectories(schema.getParent());
                Files.writeString(schema,RESULT_SCHEMA,StandardOpenOption.CREATE_NEW);
                command.add(command.size()-1,"--output-schema");
                command.add(command.size()-1,schema.toString());
                command.add(command.size()-1,"-c");
                command.add(command.size()-1,"model_reasoning_effort=\""+config.reasoningEffort()+"\"");
                if(!config.codexModel().isBlank()) {
                    command.add(command.size()-1,"--model");
                    command.add(command.size()-1,config.codexModel());
                }
            }
            ProcessBuilder builder=new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
            Map<String,String> env=builder.environment();
            Map<String,String> keep=new HashMap<>();
            for(String key:List.of("PATH","HOME","USER","TMPDIR","LANG","JAVA_HOME"))if(env.containsKey(key))keep.put(key,env.get(key));
            env.clear();
            env.putAll(keep);
            if(!verify&&!files.treeHash(workspace.resolve("approved-inputs").resolve(claim.task().skill())).equals(claim.skillHash()))throw new IOException("Reviewed skill copy hash changed");
            process=builder.start();
            service.processStarted(claim,process);
            Process current=process;
            String prompt="Use only the supplied project copy and approved inputs. Do not access operator tokens, live DB, assessment PDF, auth files, unrelated local files, network secrets, or original checkout. No publishing, deployment, arbitrary external action or self approval. Existing sandbox is a write boundary, not proven read/network isolation. Read the exact supplied versioned SKILL.md before doing the task. Analysis, review, and handoff return narratives only as JSON and write no files. Selected skill: approved-inputs/"+claim.task().skill()+"/SKILL.md\nInvocation: "+claim.invocationId()+"\nInput hash: "+claim.inputHash()+"\nRequirement: "+claim.requirement()+"\nTask: "+claim.task().prompt()+"\nAllowed write paths: "+claim.task().allowedPaths()+"\nDeclared dependency evidence:\n"+service.declaredInputs(claim)+"\nReturn exactly JSON with summary (string), questions (array), tasks (array), risks (array). These are proposals, never authority. Do not claim independent Maven verification passed. Do not modify pom.xml verification plugins to bypass checks.";
            FutureTask<Long> reader=new FutureTask<>(()->capture(current.getInputStream(),capture));
            Thread.ofVirtual().start(reader);
            FutureTask<Void> writer=new FutureTask<>(()-> {
                try(OutputStream out=current.getOutputStream()) {
                    if(!verify)out.write(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return null;
            }
            );
            Thread.ofVirtual().start(writer);
            int timeout=verify?config.verificationTimeoutSeconds():config.agentTimeoutSeconds();
            boolean timeoutHit=false,cancelled=false;
            while(!process.waitFor(1,TimeUnit.SECONDS)) {
                if(!service.current(claim)) {
                    cancelled=true;
                    break;
                }
                if((System.nanoTime()-started)/1_000_000_000L>timeout) {
                    timeoutHit=true;
                    break;
                }
                if(reader.isDone())reader.get();
                service.heartbeat(claim);
            }
            if(timeoutHit||cancelled) {
                boolean killed=terminateIdentity(process.pid(),process.info().startInstant().orElse(null));
                return failed(claim,(timeoutHit?"Invocation deadline exceeded":"Attempt cancelled or fenced")+"; process termination confirmed="+killed,false,-1);
            }
            writer.get(5,TimeUnit.SECONDS);
            reader.get(5,TimeUnit.SECONDS);
            int exit=process.exitValue();
            String transcript=Files.readString(capture);
            String output=verify?"":Files.exists(result)?Files.readString(result):"";
            if(exit!=0) {
                boolean transientFailure=!verify&&(transcript.contains("429")||transcript.contains("503")||transcript.contains("connection reset"));
                if(write)files.compensate(claim.workspace(),claim.baseline());
                return failed(claim,(verify?"Actual Maven verification failed: ":"Actual Codex failed: ")+tail(transcript),transientFailure,exit);
            }
            List<String> questions=List.of();
            if(!verify) {
                if(output.length()>1024*1024)throw new IOException("Final agent result exceeds 1 MiB");
                var node=json.readTree(output);
                if(!node.isObject()||!node.path("summary").isString()||!node.path("questions").isArray()||!node.path("tasks").isArray()||!node.path("risks").isArray())throw new IOException("Agent result violates structured contract");
                if(node.has("approved")||node.has("actions")||node.has("commands")||node.has("testPassed"))throw new IOException("Agent attempted to supply server authority");
                questions=questionList(output);
            }
            if(!verify&&(!files.treeHash(workspace.resolve("approved-inputs").resolve(claim.task().skill())).equals(claim.skillHash())||!Files.readString(workspace.resolve("approved-inputs/result-schema.json")).equals(RESULT_SCHEMA)))throw new IOException("Approved skill/schema inputs changed during execution");
            files.checkChanges(files.owned(claim.baseline()),workspace,verify?List.of():claim.task().allowedPaths());
            // Review and deterministic checks retain the exact implementation candidate hash.
            boolean awaitingInput=blocksForQuestions(claim.task(),questions);
            Path candidate=write&&!awaitingInput?files.publish(claim.runId(),claim.attemptId(),workspace,claim.task().allowedPaths(),claim.baseline()):files.owned(claim.baseline());
            String candidateHash=files.treeHash(candidate);
            String evidence=json.writeValueAsString(Map.of("execution","actual","invocationId",claim.invocationId(),"inputHash",claim.inputHash(),"candidateHash",candidateHash,"exitCode",exit,"sandbox",verify?"filtered environment; fixed Maven verify":write?"workspace-write":"read-only","result",verify?"Actual Maven verify exit 0":output,"transcript",transcript,"monetaryCost","unavailable","tokenUsage",usage(transcript).isEmpty()?"unavailable":usage(transcript)));
            Path artifact=files.evidence(claim.runId(),claim.attemptId(),evidence);
            return new Outcome(!awaitingInput,false,awaitingInput?"Agent clarification required":verify?"Actual Maven verify passed against exact candidate":"Actual Codex structured result published",artifact.toString(),WorkspaceStore.hash(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8)),candidate.toString(),candidateHash,exit,usage(transcript),questions);
        }
        catch(Exception e) {
            boolean stopped=process==null||!process.isAlive()||terminateIdentity(process.pid(),process.info().startInstant().orElse(null));
            try {
                if(write&&stopped)files.compensate(claim.workspace(),claim.baseline());
            }
            catch(Exception ignored) {
            }
            return failed(claim,"Execution/policy safe stop: "+e.getMessage()+"; process termination confirmed="+stopped,false,-1);
        }
    }
    static boolean blocksForQuestions(Task task,List<String> questions) {
        return !questions.isEmpty()&&!"shortener-handoff".equals(task.skill());
    }
    List<String> questionList(String output)throws IOException {
        var node=json.readTree(output).path("questions");
        if(!node.isArray()||node.size()>10)throw new IOException("Agent questions exceed bounded structured contract");
        List<String> questions=new ArrayList<>();
        for(var question:node) {
            if(!question.isString()||question.asString().isBlank()||question.asString().length()>2048)throw new IOException("Invalid or oversized agent question");
            questions.add(question.asString());
        }
        return List.copyOf(questions);
    }
    private static final String RESULT_SCHEMA="""
 {"type":"object","additionalProperties":false,"required":["summary","questions","tasks","risks"],"properties":{
 "summary":{"type":"string"},"questions":{"type":"array","maxItems":10,"items":{"type":"string","maxLength":2048}},"risks":{"type":"array","items":{"type":"string"}},
 "tasks":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["id","description","dependencies","allowedPaths"],"properties":{"id":{"type":"string"},"description":{"type":"string"},"dependencies":{"type":"array","items":{"type":"string"}},"allowedPaths":{"type":"array","items":{"type":"string"}}}}}}
 }
 """;
    private static long capture(InputStream in,Path output)throws IOException {
        long total=0,event=0;
        try(in;
        OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {
            byte[] b=new byte[8192];
            int n;
            while((n=in.read(b))>=0) {
                total+=n;
                if(total>20L*1024*1024)throw new IOException("20 MiB capture limit exceeded");
                for(int i=0;i<n;i++) {
                    if(b[i]=='\n')event=0;
                    else if(++event>1024*1024)throw new IOException("1 MiB event limit exceeded");
                }
                out.write(b,0,n);
            }
        }
        return total;
    }
    private Outcome failed(Claim claim,String detail,boolean transientFailure,int exit) {
        try {
            Path capture=Path.of(claim.workspace()).getParent().resolve(claim.attemptId()+".capture");
            String transcript=Files.exists(capture)?Files.readString(capture):"";
            String body=json.writeValueAsString(Map.of("execution","actual","invocationId",claim.invocationId(),"inputHash",claim.inputHash(),"exitCode",exit,"failure",detail,"transcript",transcript,"monetaryCost","unavailable","tokenUsage",usage(transcript).isEmpty()?"unknown if interrupted":usage(transcript)));
            Path evidence=files.evidence(claim.runId(),claim.attemptId()+"-failed-"+UUID.randomUUID(),body);
            return new Outcome(false,transientFailure,detail,evidence.toString(),WorkspaceStore.hash(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),null,null,exit,usage(transcript));
        }
        catch(Exception e) {
            return new Outcome(false,false,detail+"; failure evidence could not be published",null,null,null,null,exit);
        }
    }
    private Map<String,Long> usage(String transcript) {
        Map<String,Long> totals=new LinkedHashMap<>();
        for(String line:transcript.lines().toList()) {
            if(!line.startsWith("{"))continue;
            try {
                var event=json.readTree(line);
                if(!"turn.completed".equals(event.path("type").asString())||!event.path("usage").isObject())continue;
                for(String key:List.of("input_tokens","cached_input_tokens","output_tokens")) {
                    var value=event.path("usage").path(key);
                    if(value.isNumber()&&value.asLong()>=0)totals.merge(key,value.asLong(),Long::sum);
                }
            }
            catch(Exception ignored) {
            }
        }
        return totals;
    }
    private String tail(String s) {
        return s.substring(Math.max(0,s.length()-1500));
    }
    public static boolean terminateIdentity(Number pid,Object expected) {
        if(pid==null)return true;
        Optional<ProcessHandle> found=ProcessHandle.of(pid.longValue());
        if(found.isEmpty())return true;
        ProcessHandle p=found.get();
        Instant start=expected instanceof OffsetDateTime o?o.toInstant():expected instanceof Instant i?i:null;
        if(start==null||p.info().startInstant().isEmpty()||!start.equals(p.info().startInstant().get()))return false;
        List<ProcessHandle> children=p.descendants().toList();
        children.reversed().forEach(ProcessHandle::destroy);
        p.destroy();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(p.isAlive()&&System.nanoTime()<until) {
            try {
                Thread.sleep(50);
            }
            catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if(p.isAlive())p.destroyForcibly();
        try {
            p.onExit().get(2,TimeUnit.SECONDS);
        }
        catch(Exception e) {
            return false;
        }
        return children.stream().noneMatch(ProcessHandle::isAlive)&&!p.isAlive();
    }
}
