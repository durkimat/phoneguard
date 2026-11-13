# Included from vendor/phoneguard/ims/ims.mk (which patch 0006 pulls in from device/phh/treble/base.mk).
PRODUCT_PACKAGES += TT9

# Boot timing probe (see pg-timing.rc)
PRODUCT_COPY_FILES += vendor/phoneguard/apps/pg-timing.rc:system/etc/init/pg-timing.rc
