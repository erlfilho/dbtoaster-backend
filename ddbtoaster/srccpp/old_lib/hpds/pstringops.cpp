#include "pstringops.hpp"

std::ostream& operator<< (std::ostream& o, PString const& str)
{
  return o << "\"" << str.c_str() << "\"";
}
