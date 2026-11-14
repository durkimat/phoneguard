/* libfmcust.so for the FM radio on the Qin J35/J36 (MediaTek MT6631 FM, /dev/fm).
 * Lineage's FMRadio JNI (packages/apps/FMRadio/jni/fmr) dlopens libfmcust.so and calls CUST_get_cfg(); on a MediaTek device this library
 * is a vendor-supplied customisation. The stock Qin firmware does not ship it (its own libfmjni has the values built in), so we provide
 * the standard MediaTek defaults for Europe: band 87.5-108.0 MHz (units of 100 kHz), 100 kHz seek steps. */
#include <stdint.h>
#include <stddef.h>

struct fm_fake_channel;
struct fm_fake_channel_t;

struct CUST_cfg_ds {          /* must match jni/fmr/fmr.h */
    int16_t chip;
    int32_t band;
    int32_t low_band;
    int32_t high_band;
    int32_t seek_space;
    int32_t max_scan_num;
    int32_t seek_lev;
    int32_t scan_sort;
    int32_t short_ana_sup;
    int32_t rssi_th_l2;
    struct fm_fake_channel_t *fake_chan;
};

int CUST_get_cfg(struct CUST_cfg_ds *cfg)
{
    cfg->chip = 0x6631;       /* what the driver reports for MT6631; the JNI refuses to open /dev/fm if it differs */
    cfg->band = 1;            /* 1: 87.5-108.0 MHz */
    cfg->low_band = 875;
    cfg->high_band = 1080;
    cfg->seek_space = 1;      /* 1: 100 kHz */
    cfg->max_scan_num = 40;
    cfg->seek_lev = 0x0301;   /* FMR_RSSI_TH_LONG: long (wired headphone) antenna */
    cfg->scan_sort = 0;       /* FM_SCAN_SORT_NON */
    cfg->short_ana_sup = 1;   /* MT6631 can switch to its short (built-in) antenna, used when no wired headset is plugged */
    cfg->rssi_th_l2 = 0x01BD; /* FMR_MR_TH */
    cfg->fake_chan = NULL;
    return 0;
}
