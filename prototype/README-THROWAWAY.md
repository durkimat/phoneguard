# Throwaway lab prototype

`prototype/` is a **lab-only** app used to answer open questions on one phone. It is not the
controller. It exports receivers that can drive device-owner actions (hide apps, set restrictions,
write secure settings) to any app on the phone, and it is `testOnly`. Never install it on a phone
that is in daily use, and never reuse its code paths as they are.
