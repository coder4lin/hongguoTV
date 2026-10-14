# Third-party source

`hongguo.cjs` is an unmodified copy of `spider/js/红果果[短].js` from
https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
retrieved on 2026-09-21. Upstream license: GPL-3.0; see LICENSE.drpys.
The filename was changed for local loading. The original header and comments remain.
The source is loaded only by the Node content service, not bundled into the TV app.
The service adapter and HTTP layer are original project code.

Modifications 2026-10-13: the heavy drpys signature stack (x-gorgon/x-helios/
x-medusa/x-ladon/x-argus with fixed device fingerprints) was replaced by the
light X-Gorgon scheme, random per-process device ids, the `video_model/v1`
playback endpoint and the `landpage/v` comic catalog, adapted from
https://github.com/jipinwa/hongguo (native/core/provider_hongguo_sign.go and
related files, GPL-3.0). The old endpoints stopped returning data server-side.

The Android/tvOS project skeleton is derived from
@react-native-tvos/template-tv 0.81.0-0 (MIT); see LICENSE.template-tv.
