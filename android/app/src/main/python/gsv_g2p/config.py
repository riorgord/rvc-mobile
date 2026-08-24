# -*- coding: utf-8 -*-
"""手机版 G2P 数据路径配置(替代官方 Core.Resources)"""
import os

GENIE_DATA_DIR = os.getenv("GENIE_DATA_DIR", "./GenieData")
Chinese_G2P_DIR = os.getenv("Chinese_G2P_DIR", f"{GENIE_DATA_DIR}/G2P/ChineseG2P")
English_G2P_DIR = os.getenv("English_G2P_DIR", f"{GENIE_DATA_DIR}/G2P/EnglishG2P")
