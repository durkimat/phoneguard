# Included from vendor/phoneguard/ims/ims.mk (which patch 0006 pulls in from device/phh/treble/base.mk).
PRODUCT_PACKAGES += TT9

# Boot timing probe (see pg-timing.rc)
PRODUCT_COPY_FILES += vendor/phoneguard/apps/pg-timing.rc:system/etc/init/pg-timing.rc

# Phone Guard as a privileged app (the APK is staged by build.sh when present; without it the image simply has no Phone Guard).
ifneq ($(wildcard vendor/phoneguard/apps/phoneguard.apk),)
PRODUCT_PACKAGES += PhoneGuard
PRODUCT_COPY_FILES += vendor/phoneguard/apps/privapp-permissions-phoneguard.xml:system/etc/permissions/privapp-permissions-phoneguard.xml
endif
