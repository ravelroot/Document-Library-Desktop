package io.github.ravelroot.lume.settings;

/**
 * Guarda segredos (a chave de API dos provedores de IA) antes de irem para o banco.
 *
 * <p>No Windows o conteúdo é cifrado com o cofre do sistema (DPAPI, ligado à conta do Windows). Em outros sistemas,
 * ou se o cofre falhar, o texto fica só em Base64, como fazia o Electron sem cofre disponível.</p>
 */
public interface SecretVault {

    /** Texto aberto → texto guardado ("" fica ""). */
    String seal(String secret);

    /** Texto guardado → texto aberto; "" se não for possível abrir (outra conta, outro computador). */
    String open(String stored);
}
