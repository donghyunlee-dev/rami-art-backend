package com.ramiart.admin.retention.application;

import static com.ramiart.admin.retention.application.RetentionModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RetentionRepository {
    void lockGovernance(Instant now);
    boolean targetExists(String type,UUID id);
    boolean owner(UUID actor);
    Hold createHold(HoldWrite request,UUID actor,Instant now);
    Hold releaseHold(UUID id,String reason,UUID actor,Instant now);
    List<Hold> holds(String type,String status,UUID after,int limit);
    List<Candidate> candidates(String domain,Instant cutoff,Instant snapshotAt,Instant now);
    boolean processing(String domain);
    StoredRun createPreview(String domain,String policy,Instant cutoff,List<Candidate> candidates,String digest,Instant now);
    Optional<StoredRun> findRun(UUID id,boolean lock);
    Optional<StoredRun> findPreview(UUID preview,boolean lock);
    Optional<StoredRun> latest(String domain);
    Optional<StoredRun> claimNext();
    Optional<UUID> replay(UUID actor,UUID key,String hash);
    void queue(StoredRun run,UUID actor,UUID key,String hash,Instant now);
    void purge(String domain,Candidate candidate,Instant now);
    void finish(UUID id,int processed,int failed,Map<String,Integer> errors,String status,String fingerprint,Instant now);
}
