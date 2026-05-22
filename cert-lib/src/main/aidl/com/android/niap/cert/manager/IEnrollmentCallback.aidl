package com.android.niap.cert.manager;

/**
 * Asynchronous result channel for INiapCertManager.requestCertificate.
 *
 * The service invokes exactly one of onSuccess / onError per enrollment.
 * If the service dies before either is called, the caller-side
 * IBinder.linkToDeath() hook synthesises an onError.
 */
oneway interface IEnrollmentCallback {
    /** Enrollment completed: certificate is stored and ready to use. */
    void onSuccess(in byte[] certificateData);

    /** Enrollment failed: [errorMessage] is human-readable diagnostic text. */
    void onError(String errorMessage);
}
