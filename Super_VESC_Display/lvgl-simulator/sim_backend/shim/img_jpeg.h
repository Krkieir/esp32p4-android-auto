#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

esp_err_t img_jpeg_init(void);
uint8_t  *img_jpeg_alloc_rgb565(size_t bytes);
bool      img_jpeg_decode_rgb565(const uint8_t *src, size_t len,
                                 uint8_t *dst, size_t dst_cap,
                                 uint16_t *out_w, uint16_t *out_h);

#ifdef __cplusplus
}
#endif
