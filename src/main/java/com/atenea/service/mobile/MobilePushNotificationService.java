package com.atenea.service.mobile;

import com.atenea.api.mobile.MobilePushDeviceResponse;
import com.atenea.api.mobile.RegisterPushTokenRequest;
import com.atenea.api.mobile.UnregisterPushTokenRequest;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.auth.OperatorRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobilePushNotificationService {

    private final OperatorRepository operatorRepository;
    private final OperatorPushDeviceRepository operatorPushDeviceRepository;

    public MobilePushNotificationService(
            OperatorRepository operatorRepository,
            OperatorPushDeviceRepository operatorPushDeviceRepository
    ) {
        this.operatorRepository = operatorRepository;
        this.operatorPushDeviceRepository = operatorPushDeviceRepository;
    }

    @Transactional
    public MobilePushDeviceResponse registerPushToken(
            AuthenticatedOperator authenticatedOperator,
            RegisterPushTokenRequest request
    ) {
        OperatorEntity operator = operatorRepository.findById(authenticatedOperator.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(() -> new OperatorAuthenticationException("Operator account not found"));

        String normalizedToken = request.expoPushToken().trim();
        Instant now = Instant.now();
        OperatorPushDeviceEntity entity = operatorPushDeviceRepository.findByExpoPushToken(normalizedToken)
                .orElseGet(OperatorPushDeviceEntity::new);

        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setOperator(operator);
        entity.setExpoPushToken(normalizedToken);
        entity.setDeviceId(trimToNull(request.deviceId()));
        entity.setDeviceName(trimToNull(request.deviceName()));
        entity.setPlatform(request.platform().trim().toLowerCase());
        entity.setAppVersion(trimToNull(request.appVersion()));
        entity.setActive(true);
        entity.setLastRegisteredAt(now);
        entity.setUpdatedAt(now);
        return toResponse(operatorPushDeviceRepository.save(entity));
    }

    @Transactional
    public void unregisterPushToken(
            AuthenticatedOperator authenticatedOperator,
            UnregisterPushTokenRequest request
    ) {
        operatorPushDeviceRepository.findByExpoPushToken(request.expoPushToken().trim())
                .filter(device -> device.getOperator().getId().equals(authenticatedOperator.operatorId()))
                .ifPresent(device -> {
                    device.setActive(false);
                    device.setUpdatedAt(Instant.now());
                    operatorPushDeviceRepository.save(device);
                });
    }

    @Transactional(readOnly = true)
    public List<MobilePushDeviceResponse> getDevices(AuthenticatedOperator authenticatedOperator) {
        return operatorPushDeviceRepository.findByOperatorIdOrderByUpdatedAtDesc(authenticatedOperator.operatorId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private MobilePushDeviceResponse toResponse(OperatorPushDeviceEntity entity) {
        return new MobilePushDeviceResponse(
                entity.getId(),
                entity.getExpoPushToken(),
                entity.getDeviceId(),
                entity.getDeviceName(),
                entity.getPlatform(),
                entity.getAppVersion(),
                entity.isActive(),
                entity.getLastRegisteredAt(),
                entity.getUpdatedAt()
        );
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
