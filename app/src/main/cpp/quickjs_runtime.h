#pragma once
#include <string>
#include <functional>
#include <cstdint>

class QuickJsRuntime {
public:
 using Host = std::function<std::string(const std::string&, const std::string&)>;
 static std::string run(const std::string& source, const Host& host, int timeout_ms);
};
