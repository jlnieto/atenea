# Atenea app UFD T1 pilot

Only `forRealtimeSpeech()` is mapped. Its local Android/core-console area requires
`VoiceSpeechSafetyTest`; every other path is unknown and therefore T3/full.

Install the immutable published engine outside the checkout:

```bash
python3 scripts/ufd-install.py --base <trusted-base> --destination /tmp/atenea-ufd
```

Plan and run with the installed interpreter and wheel:

```bash
/tmp/atenea-ufd/venv/bin/python -I scripts/ufd-android.py plan --base <trusted-base> --wheel /tmp/atenea-ufd/universal_fast_delivery-0.2.0-py3-none-any.whl
/tmp/atenea-ufd/venv/bin/python -I scripts/ufd-android.py run
```

The focal run invokes the canonical Docker builder with
`:core-console:test --tests com.atenea.android.coreconsole.VoiceSpeechSafetyTest`.
Library tests use `android/.cache/android-home`; they neither read platform secrets
nor require a platform path. APK publication and backend deployment are not UFD
requirements for this area.
