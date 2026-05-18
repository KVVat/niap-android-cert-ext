package com.android.niap.cert.manager;

import android.os.Bundle;

interface INiapCertManager {
    void requestCertificate(
        String alias,
        String estServerUrl,
        String authToken,
        String subjectDn,
        in List<String> sans,
        String keyType,
        String sigAlg,
        String trustedCaPem,
        in Bundle validatorConfig
    );
    String getCertificateStatus(String alias);
    byte[] getCertificateData(String alias);
    String getErrorMessage(String alias);
    void revokeCertificate(String alias);
}
