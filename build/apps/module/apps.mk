# Included from vendor/phoneguard/ims/ims.mk (which patch 0006 pulls in from device/phh/treble/base.mk).
PRODUCT_PACKAGES += TT9

# Boot timing probe (see pg-timing.rc)
PRODUCT_COPY_FILES += vendor/phoneguard/apps/pg-timing.rc:system/etc/init/pg-timing.rc

# Phone Guard as a privileged app (the APK is staged by build.sh when present; without it the image simply has no Phone Guard).
ifneq ($(wildcard vendor/phoneguard/apps/phoneguard.apk),)
PRODUCT_PACKAGES += PhoneGuard
PRODUCT_COPY_FILES += vendor/phoneguard/apps/privapp-permissions-phoneguard.xml:system/etc/permissions/privapp-permissions-phoneguard.xml \
    vendor/phoneguard/apps/default-permissions-phoneguard.xml:system/etc/default-permissions/default-permissions-phoneguard.xml
endif

# First-boot device-owner provisioning (script + init service; sepolicy in build/sepolicy/pg_provision.te).
PRODUCT_COPY_FILES += \
    vendor/phoneguard/apps/pg-provision.sh:system/system_ext/bin/pg-provision.sh \
    vendor/phoneguard/apps/pg-provision.rc:system/system_ext/etc/init/pg-provision.rc

# FM radio: Lineage ships MediaTek-derived FMRadio (packages/apps/FMRadio, platform-signed with our key, with its own libfmjni and allowlist).
# (The stock Qin FMRadio.apk + libfmjni are kept as a fallback in /archive/phone-backups/own-build-inputs/fmradio-stock-20261114, see docs/fm-radio.md.)
PRODUCT_PACKAGES += FMRadio
$(call inherit-product-if-exists, vendor/phoneguard/fm/fm.mk)
