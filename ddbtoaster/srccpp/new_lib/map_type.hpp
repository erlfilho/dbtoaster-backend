#ifndef DBTOASTER_MAP_HPP
#define DBTOASTER_MAP_HPP

#include <array>
#include <stdexcept>
#include <utility>
#include <cstring>

namespace dbtoaster {

template <size_t> struct Index_{};

template <class T>
struct EqualTo {
  constexpr bool operator()(const T& x, const T& y) const {
    return x == y;
  }
};

template <>
struct EqualTo<const char*> {
  constexpr bool operator()(const char* x, const char* y) const {
    return std::strcmp(x, y) == 0;
  }
};

template<class Key, class Value, size_t N, 
         class KeyEqual = EqualTo<Key>,
         class ValueEqual = EqualTo<Value>>
struct Map {
  using Pair = std::pair<Key, Value>;

  std::array<Pair, N> data;

  constexpr Value get(const Key& key) const {
    return (find(key) != N) ? 
           data[find(key)].second :
           throw std::range_error("Key not found");
  }

  constexpr Value getOrElse(const Key& key, const Value& value) const {
    return (find(key) != N) ? data[find(key)].second : value;
  }

  constexpr bool exists(const Key& key) const {
    return find(key) != N;
  }

  constexpr bool exists(const Key& key, const Value& value) const {
    return find(key) != N && ValueEqual{}(data[find(key)].second, value);
  }
 
 private:
  constexpr size_t find(const Key& key) const {
    return find(key, Index_<N - 1>{});
  }

  template <size_t I>
  constexpr size_t find(const Key& key, Index_<I>) {
    return KeyEqual{}(data[I].first, key) ? I : find(key, Index_<I - 1>{});
  }

  constexpr size_t find(const Key& key, Index_<0>) {
    return KeyEqual{}(data[0].first, key) ? 0 : N;
  }
};

template<std::size_t Size>
using CStringMap = Map<const char*, const char*, Size>;

using CStringEqualTo = EqualTo<const char*>;

}

#endif /* DBTOASTER_MAP_HPP */