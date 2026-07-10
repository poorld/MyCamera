/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * V1.0
 */
/*History
*Date                  Modification                                 Reason
*
*/

#ifndef _SENSOR_GC6603_MIPI_RAW_H_
#define _SENSOR_GC6603_MIPI_RAW_H_


#include <utils/Log.h>
#include "sensor.h"
#include "jpeg_exif_header.h"
#include "sensor_drv_u.h"
#include "sensor_raw.h"

//#include "parameters/sensor_gc6603_raw_param_main.c"


#define VENDOR_NUM           1
#define SENSOR_NAME          "gc6603_mipi_raw"

#define I2C_SLAVE_ADDR       0x62 	/*0x20*//* 8bit slave address*/

#define GC6603_PID_ADDR     0x03f2
#define GC6603_PID_VALUE    0x56
#define GC6603_VER_ADDR     0x03f3
#define GC6603_VER_VALUE    0x23

/* sensor parameters begin */

/* effective sensor output image size */
#define PREVIEW_WIDTH        2304
#define PREVIEW_HEIGHT       1296
#define SNAPSHOT_WIDTH       2304
#define SNAPSHOT_HEIGHT      1296

/*Raw Trim parameters*/
#define PREVIEW_TRIM_X			0
#define PREVIEW_TRIM_Y			0
#define PREVIEW_TRIM_W			2304
#define PREVIEW_TRIM_H			1296
#define SNAPSHOT_TRIM_X			0
#define SNAPSHOT_TRIM_Y			0
#define SNAPSHOT_TRIM_W			2304
#define SNAPSHOT_TRIM_H			1296
/*Mipi output*/
#define LANE_NUM             4
#define RAW_BITS             10

#define PREVIEW_MIPI_PER_LANE_BPS      516 /* 2*Mipi clk */
#define SNAPSHOT_MIPI_PER_LANE_BPS     516 /* 2*Mipi clk */

/*line time unit: 1ns*/
#define PREVIEW_LINE_TIME       15504
#define SNAPSHOT_LINE_TIME      15504

/* frame length*/
#define PREVIEW_FRAME_LENGTH    2150
#define SNAPSHOT_FRAME_LENGTH   2150

/* please ref your spec */
#define FRAME_OFFSET			16
#define SENSOR_MAX_GAIN			0x1000 /*12x*/
#define SENSOR_BASE_GAIN		0x40
#define SENSOR_MIN_SHUTTER		4

/* please ref your spec
 * 1 : average binning
 * 2 : sum-average binning
 * 4 : sum binning
 */
#define BINNING_FACTOR			1

/* please ref spec
 * 1: sensor auto caculate
 * 0: driver caculate
 */
/* sensor parameters end */

/* isp parameters, please don't change it*/
#define ISP_BASE_GAIN			0x80

/* please don't change it */
#define EX_MCLK				24

/* SENSOR MIRROR FLIP INFO */
#define GC6603_MIRROR_NORMAL    1
#define GC6603_MIRROR_H         0
#define GC6603_MIRROR_V         0
#define GC6603_MIRROR_HV        0

#if GC6603_MIRROR_NORMAL
#define GC6603_MIRROR	        0x00
#elif GC6603_MIRROR_H
#define GC6603_MIRROR	        0x01
#elif GC6603_MIRROR_V
#define GC6603_MIRROR	        0x02
#elif GC6603_MIRROR_HV
#define GC6603_MIRROR	        0x03
#else
#define GC6603_MIRROR	        0x00
#endif


/*==============================================================================
 * Description:
 * register setting
 *============================================================================*/

static const SENSOR_REG_T gc6603_init_setting[] = {
		//version:v2.1.0
			//AEC:release_v2.1.0_Liner@30FPS_GC6603_AEC机制使用说明_20240805.txt
			//<MODE_1 type="01_GC6603_MIPI4L_24M_2688x2048_30fps_raw10_linear">
			//mclk  24 Mhz
			//mipi 516 Mbps/lane
			//vts = 2150
			//window 2688×2048
			//row time=15.504us
			//bayer order  rggb
			{0x03fe,0xf0},
			{0x03fe,0x00},
			{0x03fe,0x10},
			{0x0938,0x01},
			{0x0360,0xfd},
			{0x091b,0x1a},
			{0x091c,0x18},
			{0x091e,0x00},
			{0x091d,0x06},
			{0x091f,0x81},
			{0x0920,0xa1},
			{0x0922,0x3a},
			{0x0923,0x10},
			{0x0928,0x01},
			{0x0934,0xb7},
			{0x0935,0x06},
			{0x0936,0x00},
			{0x0937,0x81},
			{0x031b,0x00},
			{0x031c,0x4f},
			{0x031e,0x00},
			{0x03e0,0x00},
			{0x0314,0x10},
			{0x0219,0x47},
			{0x022b,0x10},
			{0x0259,0x08},
			{0x025a,0x44},
			{0x025b,0x10},
			{0x0340,0x08},
			{0x0341,0x66},
			{0x0342,0x03},
			{0x0343,0xe8},
			{0x0346,0x00},
			{0x0347,0x40},
			{0x0348,0x0a},
			{0x0349,0x90},
			{0x034a,0x08},
			{0x034b,0x20},
			{0x034e,0x0a},
			{0x034f,0xc0},
			{0x070c,0x03},
			{0x070d,0x00},
			{0x070e,0x98},
			{0x070f,0x0a},
			{0x0053,0x05},
			{0x0098,0x01},
			{0x0099,0x78},
			{0x009a,0x00},
			{0x009b,0xc0},
			{0x0094,0x09},
			{0x0095,0x00},
			{0x0096,0x05},
			{0x0097,0x10},
			{0x0e4c,0x3c},
			{0x0902,0x0b},
			{0x0903,0x15},
			{0x0904,0x14},
			{0x0907,0x14},
			{0x0908,0x15},
			{0x090e,0x26},
			{0x090f,0x15},
			{0x0244,0x75},
			{0x0724,0x0c},
			{0x0727,0x0c},
			{0x072a,0x18},
			{0x072b,0x19},
			{0x0709,0x40},
			{0x0719,0x40},
			{0x0912,0x01},
			{0x0913,0x00},
			{0x0e66,0x10},
			{0x0e69,0x80},
			{0x0e6a,0xc0},
			{0x0e6b,0x02},
			{0x0223,0x00},
			{0x0e81,0x02},
			{0x0e30,0x00},
			{0x0e33,0x80},
			{0x0242,0x65},
			{0x0361,0xbc},
			{0x0362,0x0f},
			{0x0e34,0x04},
			{0x0e47,0x55},
			{0x0e61,0x0d},
			{0x0e62,0x0d},
			{0x023a,0x05},
			{0x0e64,0x0c},
			{0x0e20,0x0c},
			{0x0e6e,0x50},
			{0x0e6f,0x58},
			{0x0e70,0x24},
			{0x0e71,0x28},
			{0x0e28,0x38},
			{0x0e4d,0x80},
			{0x0245,0x08},
			{0x0240,0x06},
			{0x0e63,0x06},
			{0x0236,0x02},
			{0x0261,0x60},
			{0x0262,0x28},
			{0x0072,0x00},
			{0x0074,0x01},
			{0x0087,0x53},
			{0x0704,0x07},
			{0x0705,0x28},
			{0x0706,0x02},
			{0x0715,0x28},
			{0x0716,0x02},
			{0x0708,0xc0},
			{0x0718,0xc0},
			{0x0076,0x01},
			{0x021a,0x10},
			{0x0052,0x02},
			{0x0448,0x06},
			{0x0449,0x04},
			{0x044a,0x04},
			{0x044b,0x06},
			{0x044c,0x78},
			{0x044d,0x7a},
			{0x044e,0x7a},
			{0x044f,0x78},
			{0x0046,0x30},
			{0x0002,0xa9},
			{0x0005,0x83},
			{0x0006,0x83},
			{0x001a,0x83},
			{0x0075,0x65},
			{0x0202,0x08},
			{0x0203,0x46},
			{0x0914,0x01},
			{0x0915,0x00},
			{0x0225,0x00},
			{0x0e67,0x0f},
			{0x0e68,0x0f},
			{0x0089,0x03},
			{0x0144,0x00},
			{0x0122,0x0b},
			{0x0123,0x27},
			{0x0126,0x0a},
			{0x0129,0x0b},
			{0x012a,0x0d},
			{0x012b,0x0b},
			{0x0180,0x46},
			{0x0181,0xf0},
			{0x0185,0x01},
			{0x0106,0x38},
			{0x010d,0x0b},
			{0x010e,0x40},
			{0x0111,0x2b},
			{0x0112,0x0a},
			{0x0113,0x0a},
			{0x0114,0x03},
			{0x0100,0x09},
			{0x0221,0x05},
			{0x023b,0x13},
			{0x0352,0x70},
			{0x0357,0x00},
			{0x0b00,0x40},
			{0x08ef,0x01},
			{0x03fe,0x00},
			{0x031f,0x01},
			{0x031f,0x00},
			{0x0318,0x0e},
			{0x0a67,0x80},
			{0x0a50,0x41},
			{0x0a51,0x41},
			{0x0a52,0x41},
			{0x0a54,0x26},
			{0x0a55,0x26},
			{0x0a4e,0x0c},
			{0x0a4f,0x0c},
			{0x0a65,0x17},
			{0x0a53,0x00},
			{0x0a98,0x04},
			{0x05be,0x00},
			{0x05a9,0x01},
			{0x0a67,0x80},
			{0x0023,0x00},
			{0x0025,0x00},
			{0x0028,0x0a},
			{0x0029,0x90},
			{0x002a,0x08},
			{0x002b,0x20},
			{0x0a8b,0x0a},
			{0x0a8a,0x90},
			{0x0a89,0x08},
			{0x0a88,0x20},
			{0x0a70,0x07},
			{0x0a73,0xe0},
			{0x0a80,0x7b},
			{0x0a82,0x00},
			{0x0a83,0x80},
			{0x0a5a,0x80},
		    {SENSOR_WRITE_DELAY,0x20},
			////////// delay 20
			{0x05be,0x01},
			{0x0a70,0x00},
			{0x0080,0x02},
			{0x0021,0x40},
			{0x0a67,0x00},
};

static const SENSOR_REG_T gc6603_preview_setting[] = {
	{0x0202, 0x03}, 
	{0x0203, 0xf6},


};

static const SENSOR_REG_T gc6603_snapshot_setting[] = {

{0x0202, 0x03}, 
{0x0203, 0xf6},

};


static struct sensor_res_tab_info s_gc6603_resolution_tab_raw[VENDOR_NUM] = {
	{
      .module_id = MODULE_SUNNY,
      .reg_tab = {
        {ADDR_AND_LEN_OF_ARRAY(gc6603_init_setting), PNULL, 0,
        .width = 0, .height = 0,
        .xclk_to_sensor = EX_MCLK, .image_format = SENSOR_IMAGE_FORMAT_RAW},
        /*	
        {ADDR_AND_LEN_OF_ARRAY(gc6603_preview_setting), PNULL, 0,
        .width = PREVIEW_WIDTH, .height = PREVIEW_HEIGHT,
        .xclk_to_sensor = EX_MCLK, .image_format = SENSOR_IMAGE_FORMAT_RAW},
        */
        {ADDR_AND_LEN_OF_ARRAY(gc6603_snapshot_setting), PNULL, 0,
        .width = SNAPSHOT_WIDTH, .height = SNAPSHOT_HEIGHT,
        .xclk_to_sensor = EX_MCLK, .image_format = SENSOR_IMAGE_FORMAT_RAW}
		}
	}

/*If there are multiple modules,please add here*/
};

static SENSOR_TRIM_T s_gc6603_resolution_trim_tab[VENDOR_NUM] = {
{
     .module_id = MODULE_SUNNY,
     .trim_info = {
       {0, 0, 0, 0, 0, 0, 0, {0, 0, 0, 0}},
	   /*
	   {.trim_start_x = PREVIEW_TRIM_X, .trim_start_y = PREVIEW_TRIM_Y,
        .trim_width = PREVIEW_TRIM_W,   .trim_height = PREVIEW_TRIM_H,
        .line_time = PREVIEW_LINE_TIME, .bps_per_lane = PREVIEW_MIPI_PER_LANE_BPS,
        .frame_line = PREVIEW_FRAME_LENGTH,
        .scaler_trim = {.x = PREVIEW_TRIM_X, .y = PREVIEW_TRIM_Y, .w = PREVIEW_TRIM_W, .h = PREVIEW_TRIM_H}},
       */
	   {
        .trim_start_x = SNAPSHOT_TRIM_X, .trim_start_y = SNAPSHOT_TRIM_Y,
        .trim_width = SNAPSHOT_TRIM_W,   .trim_height = SNAPSHOT_TRIM_H,
        .line_time = SNAPSHOT_LINE_TIME, .bps_per_lane = SNAPSHOT_MIPI_PER_LANE_BPS,
        .frame_line = SNAPSHOT_FRAME_LENGTH,
        .scaler_trim = {.x = SNAPSHOT_TRIM_X, .y = SNAPSHOT_TRIM_Y, .w = SNAPSHOT_TRIM_W, .h = SNAPSHOT_TRIM_H}},
		}
	}

    /*If there are multiple modules,please add here*/

};

static SENSOR_REG_T gc6603_shutter_reg[] = {
	{0x0202, 0x03}, 
	{0x0203, 0xf6}, 
			  
};

static struct sensor_i2c_reg_tab gc6603_shutter_tab = {
    .settings = gc6603_shutter_reg, 
	.size = ARRAY_SIZE(gc6603_shutter_reg),
};

static SENSOR_REG_T gc6603_again_reg[] = {
//0914, 0915, 0225, 0e67, 0e68, 0242   
				 
	{0x0914, 0x01},//0
	{0x0915, 0x00},
	{0x0225, 0x04},
	{0x0e67, 0x0d},
	{0x0e68, 0x0d},
	{0x0242, 0x65},
	{0x0064, 0x01},//7
	{0x0065, 0x00},//8 for Dig gain
};

static struct sensor_i2c_reg_tab gc6603_again_tab = {
    .settings = gc6603_again_reg, 
	.size = ARRAY_SIZE(gc6603_again_reg),
};

static SENSOR_REG_T gc6603_dgain_reg[] = {

};

static struct sensor_i2c_reg_tab gc6603_dgain_tab = {
    .settings = gc6603_dgain_reg, 
	.size = ARRAY_SIZE(gc6603_dgain_reg),
};

static SENSOR_REG_T gc6603_frame_length_reg[] = {
	{0x0340,0x08},
	{0x0341,0xca},
};

static struct sensor_i2c_reg_tab gc6603_frame_length_tab = {
    .settings = gc6603_frame_length_reg,
    .size = ARRAY_SIZE(gc6603_frame_length_reg),
};

static struct sensor_aec_i2c_tag gc6603_aec_info = {
    .slave_addr = (I2C_SLAVE_ADDR >> 1),
    .addr_bits_type = SENSOR_I2C_REG_16BIT,
    .data_bits_type = SENSOR_I2C_VAL_8BIT,
    .shutter = &gc6603_shutter_tab,
    .again = &gc6603_again_tab,
    .dgain = &gc6603_dgain_tab,
    .frame_length = &gc6603_frame_length_tab,
};


static SENSOR_STATIC_INFO_T s_gc6603_static_info[VENDOR_NUM] = {
    {.module_id = MODULE_SUNNY,
     .static_info = {
        .f_num = 200,
        .focal_length = 354,
        .max_fps = 30,
        .max_adgain = 64,
        .ois_supported = 0,
		.pdaf_supported = 0,
        .exp_valid_frame_num = 1,
        .clamp_level = 64,
        .adgain_valid_frame_num = 1,
        .fov_info = {{4.614f, 3.444f}, 4.222f}}
    }
    /*If there are multiple modules,please add here*/
};

static SENSOR_MODE_FPS_INFO_T s_gc6603_mode_fps_info[VENDOR_NUM] = {
    {.module_id = MODULE_SUNNY,
       {.is_init = 0,
         {{SENSOR_MODE_COMMON_INIT, 0, 1, 0, 0},
         {SENSOR_MODE_PREVIEW_ONE, 0, 1, 0, 0},
         {SENSOR_MODE_SNAPSHOT_ONE_FIRST, 0, 1, 0, 0},
         {SENSOR_MODE_SNAPSHOT_ONE_SECOND, 0, 1, 0, 0},
         {SENSOR_MODE_SNAPSHOT_ONE_THIRD, 0, 1, 0, 0},
         {SENSOR_MODE_PREVIEW_TWO, 0, 1, 0, 0},
         {SENSOR_MODE_SNAPSHOT_TWO_FIRST, 0, 1, 0, 0},
         {SENSOR_MODE_SNAPSHOT_TWO_SECOND, 0, 1, 0, 0},
         {SENSOR_MODE_SNAPSHOT_TWO_THIRD, 0, 1, 0, 0}}}
    }
    /*If there are multiple modules,please add here*/
};


static struct sensor_module_info s_gc6603_module_info_tab[VENDOR_NUM] = {
    {.module_id = MODULE_SUNNY,
     .module_info = {
         .major_i2c_addr = I2C_SLAVE_ADDR >> 1,
         .minor_i2c_addr = I2C_SLAVE_ADDR >> 1,

         .reg_addr_value_bits = SENSOR_I2C_REG_16BIT | SENSOR_I2C_VAL_8BIT |
                                SENSOR_I2C_FREQ_400,

         .avdd_val = SENSOR_AVDD_2800MV,
         .iovdd_val = SENSOR_AVDD_1800MV,
         .dvdd_val = SENSOR_AVDD_1200MV,

         .image_pattern = SENSOR_IMAGE_PATTERN_RAWRGB_R,

         .preview_skip_num = 1,
         .capture_skip_num = 1,
         .flash_capture_skip_num = 3,
         .mipi_cap_skip_num = 0,
         .preview_deci_num = 0,
         .video_preview_deci_num = 0,

         .threshold_eb = 0,
         .threshold_mode = 0,
         .threshold_start = 0,
         .threshold_end = 0,

         .sensor_interface = {
              .type = SENSOR_INTERFACE_TYPE_CSI2,
              .bus_width = LANE_NUM,
              .pixel_width = RAW_BITS,
 		      .is_loose = 2,

          },
         .change_setting_skip_num = 1,
         .horizontal_view_angle = 65,
         .vertical_view_angle = 60
      }
    }

/*If there are multiple modules,please add here*/
};

static struct sensor_ic_ops s_gc6603_ops_tab;
struct sensor_raw_info *s_gc6603_mipi_raw_info_ptr = PNULL;


/*==============================================================================
 * Description:
 * sensor all info
 * please modify this variable acording your spec
 *============================================================================*/
SENSOR_INFO_T g_gc6603_mipi_raw_info = {
    .hw_signal_polarity = SENSOR_HW_SIGNAL_PCLK_P | SENSOR_HW_SIGNAL_VSYNC_P |
                          SENSOR_HW_SIGNAL_HSYNC_P,
    .environment_mode = SENSOR_ENVIROMENT_NORMAL | SENSOR_ENVIROMENT_NIGHT,
    .image_effect = SENSOR_IMAGE_EFFECT_NORMAL |
                    SENSOR_IMAGE_EFFECT_BLACKWHITE | SENSOR_IMAGE_EFFECT_RED |
                    SENSOR_IMAGE_EFFECT_GREEN | SENSOR_IMAGE_EFFECT_BLUE |
                    SENSOR_IMAGE_EFFECT_YELLOW | SENSOR_IMAGE_EFFECT_NEGATIVE |
                    SENSOR_IMAGE_EFFECT_CANVAS,

    .wb_mode = 0,
    .step_count = 7,
    .reset_pulse_level = SENSOR_LOW_PULSE_RESET,
    .reset_pulse_width = 50,
    .power_down_level = SENSOR_LOW_LEVEL_PWDN,
    .identify_count = 1,
    .identify_code =
        {{ .reg_addr = GC6603_PID_ADDR, .reg_value = GC6603_PID_VALUE},
         { .reg_addr = GC6603_VER_ADDR, .reg_value = GC6603_VER_VALUE}},

    .source_width_max = SNAPSHOT_WIDTH,
    .source_height_max = SNAPSHOT_HEIGHT,
    .name = (cmr_s8 *)SENSOR_NAME,
    .image_format = SENSOR_IMAGE_FORMAT_RAW,

    .module_info_tab = s_gc6603_module_info_tab,
    .module_info_tab_size = ARRAY_SIZE(s_gc6603_module_info_tab),

    .resolution_tab_info_ptr = s_gc6603_resolution_tab_raw,
    .sns_ops = &s_gc6603_ops_tab,
    .raw_info_ptr = &s_gc6603_mipi_raw_info_ptr,

    .video_tab_info_ptr = NULL,
    .sensor_version_info = (cmr_s8 *)"gc6603_v1",
};

#endif
