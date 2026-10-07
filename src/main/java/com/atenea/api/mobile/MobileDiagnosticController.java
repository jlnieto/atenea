package com.atenea.api.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.service.mobile.MobileDiagnosticService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mobile/diagnostics")
public class MobileDiagnosticController {
    private final MobileDiagnosticService service;
    public MobileDiagnosticController(MobileDiagnosticService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MobileDiagnosticReceiptResponse> upload(@AuthenticationPrincipal AuthenticatedOperator actor,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.store(actor, file));
    }

    @GetMapping
    public ResponseEntity<List<MobileDiagnosticReceiptResponse>> recent(@AuthenticationPrincipal AuthenticatedOperator actor,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recent(actor, limit));
    }

    @GetMapping("/latest")
    public ResponseEntity<MobileDiagnosticReceiptResponse> latest(@AuthenticationPrincipal AuthenticatedOperator actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.latest(actor));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MobileDiagnosticReceiptResponse> receipt(@AuthenticationPrincipal AuthenticatedOperator actor,
            @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.receipt(actor, id));
    }

    @GetMapping(value = "/{id}/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal AuthenticatedOperator actor, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("atenea-diagnostics-" + id + ".json").build().toString())
                .header("X-Content-Type-Options", "nosniff").body(service.content(actor, id));
    }
}
