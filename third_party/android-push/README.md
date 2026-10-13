# Push protocol and authentication sources

The Android project does not presently bundle an FCM or OEM client SDK: registered application accounts, matching credentials and real token callbacks are absent. `installedSdkChannels` remains empty. No binary from the research repositories is redistributed.

The DeveloperCenter server references **Google.Apis.Auth 1.73.0**, Apache-2.0. Its `ServiceAccountCredential` supplies FCM OAuth authentication; the application does not reimplement service-account JWT signing or token refresh. Source: [google-api-dotnet-client v1.73.0](https://github.com/googleapis/google-api-dotnet-client/tree/6386ed813d1de3556ac5a4f211160b3765ba1c10). The license is retained in `Google-APIs-Apache-2.0.txt`; transitive official packages retain their licenses.

[firebase-admin-dotnet v3.7.0](https://github.com/firebase/firebase-admin-dotnet/tree/62553e0f375e77f40f479b7a3a0bbc50808f9216) (Apache-2.0) was reviewed to confirm the HTTP v1 message envelope and the same Google authentication dependency. Firebase Admin itself is not added as a dependency or copied.

[HMS official C# example](https://github.com/HMS-Core/hms-push-serverdemo-csharp/tree/dce2c626379648c5ecb9baea393329667463f151) (Apache-2.0) was reviewed for the fixed OAuth endpoint, message endpoint and accepted-response code. No example implementation is copied. `Huawei-Apache-2.0.txt` records the reviewed source's license.

[go-multipush](https://github.com/meetleev/go-multipush/tree/4a4e16c94d2083e06e8da18595b167d4f6f07c06) (Apache-2.0) provided protocol field names and official-document links for MiPush, OPPO and vivo. Its implementation is not copied because it logs secrets, leaves response sizes/timeouts unbounded and has authentication/error handling defects. `Protocol-reference-Apache-2.0.txt` records this source; production requests use application-owned bounded adapters.

Research details and exact validation limits are in [docs/research/android-push.md](../../docs/research/android-push.md).
