package com.example.shortener.links;

import com.example.shortener.analytics.AnalyticsService;
import jakarta.validation.Valid;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.example.shortener.links.LinkModels.*;

import java.net.URI;
import java.time.LocalDate;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Anonymous link creation, lifecycle management, combined details/counts, and redirects. */
@RestController
public class LinkController {
    private static final Logger LOG = LogManager.getLogger(LinkController.class);
    private final LinkService links;
    private final AnalyticsService analytics;

    public LinkController(LinkService links, AnalyticsService analytics) {
        this.links = links;
        this.analytics = analytics;
    }

    /**
     * Creates a fixed destination mapping or replays a retained identical request.
     * @param request destination, optional alias, and optional future expiry
     * @param key optional creation idempotency key
     * @return 201 with original metadata and its Location; invalid input is 400 and conflicts are 409
     */
    @PostMapping("/api/v1/links")
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        LinkResponse link = links.create(request, key);
        return ResponseEntity.created(URI.create("/api/v1/links/" + link.code())).body(link);
    }

    /**
     * Reads metadata and best-effort recorded counts, including retained tombstones.
     * @param code case-sensitive link code
     * @param from optional first UTC date in the retained range
     * @param to optional last UTC date; defaults to today
     * @return 200 with combined details/counts; unknown codes are 404 and invalid ranges are 400
     */
    @GetMapping("/api/v1/links/{code}")
    public LinkDetails get(@PathVariable String code,
                           @RequestParam(required = false) LocalDate from,
                           @RequestParam(required = false) LocalDate to) {
        return new LinkDetails(links.get(code), analytics.get(code, from, to));
    }

    /**
     * Disables a link without changing its destination or expiry.
     * @param code case-sensitive link code
     * @param request disabled=true and the expected resource version
     * @return 200 with metadata; stale versions are 409 and deleted links are 410
     */
    @PatchMapping("/api/v1/links/{code}")
    public LinkResponse disable(@PathVariable String code, @Valid @RequestBody DisableRequest request) {
        return links.disable(code, request);
    }

    /**
     * Tombstones a code while retaining its reservation and recorded counts.
     * @param code case-sensitive link code
     * @return 204, including for unknown or already deleted codes
     */
    @DeleteMapping("/api/v1/links/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        links.delete(code);
        return ResponseEntity.noContent().build();
    }

    /**
     * Resolves an active link and attempts asynchronous analytics recording.
     * @param code case-sensitive link code
     * @return 302 with the exact destination and no-store; unknown codes are 404 and inactive links are 410
     */
    @RequestMapping(value = "/r/{code}", method = RequestMethod.GET)
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        ResolvedLink link = links.resolve(code);
        try {
            analytics.enqueue(link.id());
        } catch (RuntimeException recordingUnavailable) {
            LOG.warn("Redirect analytics enqueue failed code={} id={} failureType={}",
                    code, link.id(), recordingUnavailable.getClass().getSimpleName());
        }
        return response(link);
    }

    /**
     * Resolves using GET's headers without a body or an analytics increment.
     * @param code case-sensitive link code
     * @return 302 with the exact destination and no-store; resolution errors match GET
     */
    @RequestMapping(value = "/r/{code}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String code) {
        return response(links.resolve(code));
    }

    private ResponseEntity<Void> response(ResolvedLink link) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, link.destinationUrl()).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
