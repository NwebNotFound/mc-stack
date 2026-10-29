package de.mcstack.craftcore;
final class Experience {
 static int half(int level, float progress, int next) {
  long n=level<=16 ? (long)level*level+6L*level : level<=31 ? (5L*level*level-81L*level+720)/2 : (9L*level*level-325L*level+4440)/2;
  return (int)Math.min(Integer.MAX_VALUE, (n+(long)(progress*next))/2);
 }
}
