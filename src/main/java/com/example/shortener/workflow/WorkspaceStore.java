package com.example.shortener.workflow;

import com.example.shortener.common.ErrorCodes;
import org.springframework.stereotype.Component;
import com.example.shortener.common.ApiException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.Stream;
@Component
public class WorkspaceStore {
    private final Path root,project,skills;
    private final boolean scenarioFixtures;
    public WorkspaceStore(WorkflowProperties p) {
        scenarioFixtures=p.scenarioFixtures();
        root=configured(p.workspaceRoot());
        project=configured(p.projectRoot());
        skills=configured(p.skillsRoot());
        if(root.equals(project)||project.startsWith(root))throw new IllegalArgumentException("Workflow storage must be separate from source");
    }
    private static Path configured(String value) {
        Path p=Path.of(value).toAbsolutePath().normalize(),ancestor=p;
        while(ancestor!=null&&!Files.exists(ancestor))ancestor=ancestor.getParent();
        try {
            return ancestor.toRealPath().resolve(ancestor.relativize(p)).normalize();
        }
        catch(IOException e) {
            throw new IllegalArgumentException("Cannot resolve trusted workspace configuration",e);
        }
    }
    public static void validateRelative(String value) {
        if(value==null||value.isBlank()||value.startsWith("/")||value.contains("\\")||value.contains(":"))bad("Invalid relative path");
        Path p=Path.of(value);
        if(p.isAbsolute()||!p.normalize().equals(p)||p.startsWith(".."))bad("Path traversal denied");
        for(Path part:p)if(part.toString().startsWith(".")||Set.of("target","node_modules").contains(part.toString()))bad("Hidden/cache path denied");
        if(value.toLowerCase(Locale.ROOT).endsWith(".pdf")||value.equals("approved-inputs")||value.startsWith("approved-inputs/"))bad("Assessment PDF is not an input");
    }
    private static void bad(String s) {
        throw ApiException.badRequest(ErrorCodes.WORKSPACE_POLICY,s);
    }
    public Path owned(String path) {
        Path p=Path.of(path).toAbsolutePath().normalize();
        if(!p.startsWith(root)||p.equals(root))bad("Not an owned workspace");
        checkNoLinks(p);
        return p;
    }
    private static void checkNoLinks(Path p) {
        for(Path q=p;q!=null;q=q.getParent())if(Files.isSymbolicLink(q))bad("Symlink denied");
    }
    public Path snapshot(String runId,String scenario)throws IOException {
        Path source=scenarioFixtures?project.resolve("docs/scenarios/fixtures").resolve(scenario):project;
        if(!Files.isDirectory(source))throw new IOException("Approved scenario fixture is unavailable");
        Path dest=root.resolve(runId).resolve("baseline");
        copy(source,dest,true);
        return dest;
    }
    public Path attempt(String runId,String id,String baseline)throws IOException {
        Path dest=root.resolve(runId).resolve("attempts").resolve(id);
        copy(owned(baseline),dest,false);
        return dest;
    }
    public String skillHash(String name)throws IOException {
        if(!WorkflowGraph.SKILLS.contains(name))bad("Unknown skill");
        return treeHash(skills.resolve(name));
    }
    public String skill(String name,Path workspace)throws IOException {
        if(!WorkflowGraph.SKILLS.contains(name))bad("Unknown skill");
        Path source=skills.resolve(name);
        checkNoLinks(source);
        Path target=workspace.resolve("approved-inputs").resolve(name);
        copy(source,target,false);
        return treeHash(target);
    }
    public Path publish(String runId,String attemptId,Path workspace,List<String> allowed,String baseline)throws IOException {
        checkChanges(owned(baseline),workspace,allowed);
        Path dest=root.resolve(runId).resolve("candidates").resolve(attemptId);
        copy(workspace,dest,false);
        return dest;
    }
    public Path evidence(String runId,String attemptId,String body)throws IOException {
        Path p=root.resolve(runId).resolve("evidence").resolve(attemptId+".json");
        Files.createDirectories(p.getParent());
        Files.writeString(p,body,StandardOpenOption.CREATE_NEW);
        return p;
    }
    public String readEvidence(String path,String expected)throws IOException {
        Path p=owned(path);
        String actual=hash(Files.readAllBytes(p));
        if(!actual.equals(expected))bad("Evidence hash changed");
        return Files.readString(p);
    }
    public void assertHash(String path,String expected)throws IOException {
        if(!treeHash(owned(path)).equals(expected))bad("Candidate snapshot changed");
    }
    public void compensate(String workspace,String baseline)throws IOException {
        Path w=owned(workspace);
        try(Stream<Path>s=Files.walk(w)) {
            for(Path p:s.sorted(Comparator.reverseOrder()).toList())Files.delete(p);
        }
        copy(owned(baseline),w,false);
    }
    public void checkChanges(Path baseline,Path workspace,List<String> allowed)throws IOException {
        Map<String,String> before=manifest(baseline),after=manifest(workspace);
        Set<String> names=new HashSet<>(before.keySet());
        names.addAll(after.keySet());
        for(String path:names) {
            if(path.startsWith("approved-inputs/"))continue;
            if(!Objects.equals(before.get(path),after.get(path))) {
                if(scenarioFixtures&&before.containsKey(path)&&(path.equals("pom.xml")||path.startsWith("src/test/")))bad("Pinned fixture POM and existing acceptance contracts cannot change: "+path);
                validateRelative(path);
                if(allowed.stream().noneMatch(p->p.endsWith("/")?path.startsWith(p):path.equals(p)))bad("Undeclared generated path: "+path);
            }
        }
    }
    public String treeHash(Path path)throws IOException {
        return hash(manifest(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private Map<String,String> manifest(Path path)throws IOException {
        TreeMap<String,String> m=new TreeMap<>();
        checkNoLinks(path);
        try(Stream<Path>s=Files.walk(path)) {
            for(Path p:s.sorted().toList()) {
                if(Files.isSymbolicLink(p))bad("Symlink artifact denied");
                if(Files.isRegularFile(p)) {
                    String rel=path.relativize(p).toString().replace('\\','/');
                    if(rel.startsWith("target/")||rel.startsWith("approved-inputs/"))continue;
                    if(Files.size(p)>20*1024*1024)bad("Artifact too large");
                    m.put(rel,hash(Files.readAllBytes(p)));
                }
            }
        }
        return m;
    }
    private void copy(Path source,Path target,boolean filtered)throws IOException {
        checkNoLinks(source);
        if(Files.exists(target))throw new IOException("Immutable destination already exists");
        Files.createDirectories(target);
        long[] total= {
            0
        }
        ;
        Files.walkFileTree(source,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path p,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException {
                Path rel=source.relativize(p);
                if(!rel.toString().isEmpty()&&((filtered&&p.startsWith(root))||excluded(rel,filtered)))return FileVisitResult.SKIP_SUBTREE;
                if(Files.isSymbolicLink(p))bad("Symlink input denied");
                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path p,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException {
                Path rel=source.relativize(p);
                if((filtered&&p.startsWith(root))||excluded(rel,filtered))return FileVisitResult.CONTINUE;
                if(Files.isSymbolicLink(p))bad("Symlink input denied");
                if(attrs.size()>20*1024*1024||(total[0]+=attrs.size())>100*1024*1024)bad("Input size bound exceeded");
                Files.copy(p,target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }
        }
        );
    }
    private boolean excluded(Path rel,boolean filtered) {
        for(Path part:rel) {
            String n=part.toString();
            if(n.equals("target")||n.equals("node_modules")||n.equals("logs")||n.equals("approved-inputs"))return true;
            if(filtered&&(n.startsWith(".")||n.equals("agent-skills")))return true;
        }
        String n=rel.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf")||n.endsWith(".mv.db")||n.endsWith(".trace.db")||n.endsWith(".log")||n.contains("credential")||n.contains("secret")||n.equals("auth.json")||n.equals("config.toml")||n.endsWith(".pem")||n.endsWith(".key");
    }
    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
