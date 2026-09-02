# -*- coding: utf-8 -*-
"""onnxruntime-mobile 1.18 C API ctypes 封装(Termux/arm64,手机端推理)

用法: GSV_ORT_LIB=/path/libonnxruntime.so python gsv_runner.py ...
提供与 onnxruntime.InferenceSession 兼容的接口:
  sess = gsv_ort.InferenceSession(model_path)
  out = sess.run(None, {"input": np_arr})
"""
import ctypes
import numpy as np
import os

_N_MEMBERS = 280

class _OrtApi(ctypes.Structure):
    _fields_ = [(f'fn_{j}', ctypes.c_void_p) for j in range(_N_MEMBERS)]

class _OrtApiBase(ctypes.Structure):
    _fields_ = [('GetApi', ctypes.c_void_p), ('GetVersionString', ctypes.c_void_p)]

class _OrtAllocator(ctypes.Structure):
    _fields_ = [('version', ctypes.c_uint32), ('Alloc', ctypes.c_void_p),
                ('Free', ctypes.c_void_p), ('Info', ctypes.c_void_p), ('Reserve', ctypes.c_void_p)]

def _load_lib(path):
    lib = ctypes.CDLL(path)
    lib.OrtGetApiBase.restype = ctypes.POINTER(_OrtApiBase)
    base = lib.OrtGetApiBase()
    get_api = ctypes.cast(base.contents.GetApi, ctypes.CFUNCTYPE(ctypes.POINTER(_OrtApi), ctypes.c_uint32))
    api = get_api(18).contents  # ORT_API_VERSION 18 (onnxruntime 1.18)
    return lib, api

_lib, _api = _load_lib(os.environ['GSV_ORT_LIB'])

_FN = {
    'CreateStatus': _api.fn_0,
    'GetErrorCode': _api.fn_1,
    'GetErrorMessage': _api.fn_2,
    'CreateEnv': _api.fn_3,
    'ReleaseEnv': _api.fn_92,
    'CreateSessionOptions': _api.fn_10,
    'ReleaseSessionOptions': _api.fn_100,
    'SetSessionGraphOptimizationLevel': _api.fn_23,
    'CreateSession': _api.fn_7,
    'SessionGetInputCount': _api.fn_30,
    'SessionGetInputName': _api.fn_36,
    'SessionGetOutputCount': _api.fn_31,
    'SessionGetOutputName': _api.fn_37,
    'CreateCpuMemoryInfo': _api.fn_69,
    'ReleaseMemoryInfo': _api.fn_94,
    'CreateAllocator': _api.fn_131,
    'CreateTensorWithDataAsOrtValue': _api.fn_49,
    'GetTensorTypeAndShape': _api.fn_65,
    'GetTensorElementType': _api.fn_60,
    'GetDimensionsCount': _api.fn_61,
    'GetDimensions': _api.fn_62,
    'ReleaseTensorTypeAndShapeInfo': _api.fn_99,
    'Run': _api.fn_9,
    'GetTensorMutableData': _api.fn_51,
    'ReleaseValue': _api.fn_96,
    'ReleaseSession': _api.fn_95,
}

def _cb(name, restype, *argtypes):
    """取成员函数指针并声明原型(缓存)"""
    return ctypes.cast(_FN[name], ctypes.CFUNCTYPE(restype, *argtypes))

# ---- OrtStatus 错误处理 ----
def _check(status, ctx):
    if status:
        code = _cb('GetErrorCode', ctypes.c_int, ctypes.c_void_p)(status)
        msg = _cb('GetErrorMessage', ctypes.c_char_p, ctypes.c_void_p)(status)
        raise RuntimeError(f'[ORT {code}] {ctx}: {msg.decode() if msg else ""}')

# ---- numpy <-> ONNX dtype ----
_NP2ORT = {np.dtype(t): e for t, e in [
    (np.float32, 1), (np.uint8, 2), (np.int8, 3), (np.uint16, 4), (np.int16, 5),
    (np.int32, 6), (np.int64, 7), (np.bool_, 9), (np.float16, 10), (np.float64, 11),
    (np.uint32, 12), (np.uint64, 13),
]}
_ORT2NP = {e: np.dtype(t) for t, e in [
    (np.float32, 1), (np.uint8, 2), (np.int8, 3), (np.int32, 6), (np.int64, 7),
    (np.bool_, 9), (np.float16, 10), (np.float64, 11),
]}

class _InOut:
    """模拟 onnxruntime NodeArg:只有 runner 用到的 .name"""
    def __init__(self, name):
        self.name = name
        self.shape = None
        self.type = 'tensor(float)'


class InferenceSession:
    """onnxruntime.InferenceSession 兼容接口(CPU,单线程)"""

    def __init__(self, model_path, providers=None, sess_options=None):
        _ = providers, sess_options
        self._env = ctypes.c_void_p()
        self._sess = ctypes.c_void_p()
        self._opts = ctypes.c_void_p()
        self._mem = ctypes.c_void_p()
        self._alloc = ctypes.c_void_p()
        # CreateEnv(WARNING=2)
        _check(_cb('CreateEnv', ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p,
               ctypes.POINTER(ctypes.c_void_p))(2, b'gsv_ort', ctypes.byref(self._env)), 'CreateEnv')
        # SessionOptions + graph opt level ORT_ENABLE_ALL=99
        _check(_cb('CreateSessionOptions', ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p))(
               ctypes.byref(self._opts)), 'CreateSessionOptions')
        _check(_cb('SetSessionGraphOptimizationLevel', ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int)(
               self._opts, 99), 'SetSessionGraphOptimizationLevel')
        _check(_cb('CreateSession', ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_void_p,
               ctypes.POINTER(ctypes.c_void_p))(self._env, os.fsencode(model_path), self._opts,
               ctypes.byref(self._sess)), f'CreateSession {model_path}')
        _cb('ReleaseSessionOptions', None, ctypes.c_void_p)(self._opts)
        # CPU memory info(device allocator,非 arena) + allocator(session 级,供名字分配)
        # 注意:arena(1) 的 BFCArena Free 会断言指针必须在区域内,SessionGetInputName
        # 分配的名字与之不匹配会崩;device(0) 是 malloc-backed,无此问题
        _check(_cb('CreateCpuMemoryInfo', ctypes.c_void_p, ctypes.c_int, ctypes.c_int,
               ctypes.POINTER(ctypes.c_void_p))(0, 0, ctypes.byref(self._mem)), 'CreateCpuMemoryInfo')
        _check(_cb('CreateAllocator', ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
               ctypes.POINTER(ctypes.c_void_p))(self._sess, self._mem, ctypes.byref(self._alloc)), 'CreateAllocator')
        self.inputs = self._names(True)
        self.outputs = self._names(False)

    def get_inputs(self):
        """onnxruntime 兼容:返回带 .name 的输入描述列表"""
        return [_InOut(name) for name in self.inputs]

    def get_outputs(self):
        return [_InOut(name) for name in self.outputs]

    def _names(self, is_input):
        n = ctypes.c_size_t()
        f = _cb('SessionGetInputCount' if is_input else 'SessionGetOutputCount',
               ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t))
        _check(f(self._sess, ctypes.byref(n)), 'Count')
        g = _cb('SessionGetInputName' if is_input else 'SessionGetOutputName',
               ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p,
               ctypes.POINTER(ctypes.c_char_p))
        out = []
        for j in range(n.value):
            name = ctypes.c_char_p()
            _check(g(self._sess, j, self._alloc, ctypes.byref(name)), 'Name')
            out.append(name.value.decode())
            # 注意:不释放 name——ORT 内部 arena 的 Free 会断言区域匹配,
            # 与 CreateAllocator 拿到的 CPUInput arena 不匹配会崩(BFCArena IndexFor)。
            # 一次性 ~KB 级字符串,进程内无感,直接丢弃。
        return out

    def _alloc_free(self, ptr):
        if ptr:
            _alloc = ctypes.cast(self._alloc, ctypes.POINTER(_OrtAllocator)).contents
            ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_void_p)(_alloc.Free)(self._alloc, ptr)

    def _make_value(self, arr):
        arr = np.ascontiguousarray(arr)
        if arr.dtype not in _NP2ORT:
            raise TypeError(f'unsupported dtype {arr.dtype}')
        shape = (ctypes.c_int64 * arr.ndim)(*arr.shape)
        val = ctypes.c_void_p()
        _check(_cb('CreateTensorWithDataAsOrtValue', ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
               ctypes.c_size_t, ctypes.POINTER(ctypes.c_int64), ctypes.c_size_t, ctypes.c_int,
               ctypes.POINTER(ctypes.c_void_p))(self._mem, ctypes.c_void_p(arr.ctypes.data),
               arr.nbytes, shape, arr.ndim, _NP2ORT[arr.dtype], ctypes.byref(val)), 'CreateTensor')
        return val

    def _value_info(self, val):
        """取 OrtValue 的 shape/dtype -> (shape list, np.dtype)"""
        info = ctypes.c_void_p()
        _check(_cb('GetTensorTypeAndShape', ctypes.c_void_p, ctypes.c_void_p,
               ctypes.POINTER(ctypes.c_void_p))(val, ctypes.byref(info)), 'GetTensorTypeAndShape')
        ndim = ctypes.c_size_t()
        _check(_cb('GetDimensionsCount', ctypes.c_void_p, ctypes.c_void_p,
               ctypes.POINTER(ctypes.c_size_t))(info, ctypes.byref(ndim)), 'DimensionsCount')
        dims = (ctypes.c_int64 * max(1, ndim.value))()
        if ndim.value:
            _check(_cb('GetDimensions', ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int64),
                   ctypes.c_size_t)(info, dims, ndim.value), 'Dimensions')
        etype = ctypes.c_int()
        _check(_cb('GetTensorElementType', ctypes.c_void_p, ctypes.c_void_p,
               ctypes.POINTER(ctypes.c_int))(info, ctypes.byref(etype)), 'ElementType')
        _cb('ReleaseTensorTypeAndShapeInfo', None, ctypes.c_void_p)(info)
        shape = [int(dims[j]) for j in range(ndim.value)]
        if etype.value not in _ORT2NP:
            raise TypeError(f'unsupported output type {etype.value}')
        return shape, _ORT2NP[etype.value]

    def run(self, output_names, feeds):
        names = list(feeds.keys())
        in_vals = (ctypes.c_void_p * len(feeds))()
        in_names = (ctypes.c_char_p * len(feeds))(*[n.encode() for n in names])
        try:
            for j, (k, v) in enumerate(feeds.items()):
                in_vals[j] = self._make_value(v)
            if output_names is None:
                output_names = self.outputs
            out_vals = (ctypes.c_void_p * len(output_names))()
            out_names = (ctypes.c_char_p * len(output_names))(*[n.encode() for n in output_names])
            _check(_cb('Run', ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                   ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(ctypes.c_void_p), ctypes.c_size_t,
                   ctypes.POINTER(ctypes.c_char_p), ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p))(
                   self._sess, None, in_names, in_vals, len(feeds), out_names, len(output_names), out_vals),
                   'Run')
            results = []
            for j in range(len(output_names)):
                val = out_vals[j]
                if not val:
                    raise RuntimeError(f'output {output_names[j]} is NULL')
                shape, dtype = self._value_info(val)
                data = ctypes.c_void_p()
                _check(_cb('GetTensorMutableData', ctypes.c_void_p, ctypes.c_void_p,
                       ctypes.POINTER(ctypes.c_void_p))(val, ctypes.byref(data)), 'MutableData')
                n = int(np.prod(shape)) if shape else 1
                buf = ctypes.string_at(data, n * np.dtype(dtype).itemsize)
                results.append(np.frombuffer(buf, dtype=dtype).reshape(shape) if shape
                              else np.frombuffer(buf, dtype=dtype)[0])
                _cb('ReleaseValue', None, ctypes.c_void_p)(val)
            return results
        finally:
            for j in range(len(feeds)):
                _cb('ReleaseValue', None, ctypes.c_void_p)(in_vals[j])

    def close(self):
        _cb('ReleaseSession', None, ctypes.c_void_p)(self._sess)
        _cb('ReleaseEnv', None, ctypes.c_void_p)(self._env)
        _cb('ReleaseMemoryInfo', None, ctypes.c_void_p)(self._mem)


def _selftest():
    base = _lib.OrtGetApiBase()
    gv = ctypes.cast(base.contents.GetVersionString, ctypes.CFUNCTYPE(ctypes.c_char_p))
    print('ORT version:', gv().decode())
    print('OK: lib loaded,', len(_FN), 'functions mapped')

if __name__ == '__main__':
    _selftest()
