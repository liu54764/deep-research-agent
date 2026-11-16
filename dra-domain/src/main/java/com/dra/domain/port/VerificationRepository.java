package com.dra.domain.port;

import com.dra.domain.model.VerificationRow;

public interface VerificationRepository {

    void save(VerificationRow verification);
}
