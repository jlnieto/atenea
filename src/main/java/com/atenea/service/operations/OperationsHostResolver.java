package com.atenea.service.operations;

import com.atenea.persistence.operations.ManagedHostEntity;
import com.atenea.persistence.operations.ManagedHostRepository;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsHostResolver {

    private final ManagedHostRepository managedHostRepository;

    public OperationsHostResolver(ManagedHostRepository managedHostRepository) {
        this.managedHostRepository = managedHostRepository;
    }

    @Transactional(readOnly = true)
    public ManagedHostEntity requireById(Long hostId) {
        return managedHostRepository.findByIdAndActiveTrue(hostId)
                .orElseThrow(() -> new OperationsHostNotFoundException(hostId));
    }

    @Transactional(readOnly = true)
    public ManagedHostEntity resolveFromInput(String input) {
        List<ManagedHostEntity> hosts = managedHostRepository.findByActiveTrueOrderByNameAsc();
        if (hosts.isEmpty()) {
            return null;
        }
        List<ManagedHostEntity> matches = hosts.stream()
                .filter(host -> matches(host, input))
                .sorted(Comparator.comparing(ManagedHostEntity::getName))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty() && hosts.size() == 1) {
            return hosts.getFirst();
        }
        return null;
    }

    private boolean matches(ManagedHostEntity host, String input) {
        String normalizedInput = normalize(input);
        return normalizedInput.contains(normalize(host.getName()))
                || (host.getDescription() != null && normalizedInput.contains(normalize(host.getDescription())))
                || normalizedInput.contains(normalize(host.getSshHost()));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
