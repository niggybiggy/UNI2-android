
#include "p4g_shaders.h"
#include "p4g_gxp.h"
#include "p4g_cpk.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>
#include <vector>
#include <sys/stat.h>

#if defined(_WIN32)
#include <direct.h>
#endif

namespace p4g {
namespace {

bool make_dir(
    const std::string& path,
    std::string& error
) {
    if (path.empty()) {
        error = "P4G: empty output folder";
        return false;
    }

    for (size_t i = 1; i <= path.size(); ++i) {
        if (i != path.size() && path[i] != '/') {
            continue;
        }

        std::string part = path.substr(0, i);

        if (part.empty()) {
            continue;
        }

#if defined(_WIN32)
        const int rc = _mkdir(part.c_str());
#else
        const int rc = mkdir(part.c_str(), 0755);
#endif

        if (rc != 0 && errno != EEXIST) {
            error = "P4G: could not create folder: " +
                    part + " (" + std::strerror(errno) + ")";
            return false;
        }

        struct stat info{};

        if (stat(part.c_str(), &info) != 0 ||
            !S_ISDIR(info.st_mode)) {
            error = "P4G: path is not a folder: " + part;
            return false;
        }
    }

    return true;
}

bool save_file(
    const std::string& path,
    const uint8_t* data,
    size_t size,
    std::string& error
) {
    const std::string tmp = path + ".tmp";

    FILE* f = std::fopen(tmp.c_str(), "wb");

    if (!f) {
        error = "P4G: could not open: " + tmp;
        return false;
    }

    const bool written =
        size == 0 ||
        std::fwrite(data, 1, size, f) == size;

    const bool closed = std::fclose(f) == 0;

    if (!written || !closed ||
        std::rename(tmp.c_str(), path.c_str()) != 0) {
        std::remove(tmp.c_str());
        error = "P4G: could not write: " + path;
        return false;
    }

    return true;
}

bool expected_pack_name(
    const std::string& name
) {
    static const char* names[] = {
        "output_cnv_v_standard.bin",
        "output_cnv_f_standard.bin",
        "output_cnv_v_effect.bin",
        "output_cnv_f_effect.bin",
        "output_cnv_v_sprite.bin",
        "output_cnv_f_sprite.bin"
    };

    for (const char* target : names) {
        if (name == target) {
            return true;
        }
    }

    return false;
}

// Converte bytes em texto hexadecimal.
// Nao altera os dados do shader.
std::string to_hex(
    const uint8_t* data,
    size_t size
) {
    static constexpr char digits[] =
        "0123456789ABCDEF";

    std::string result;
    result.reserve(size * 2);

    for (size_t i = 0; i < size; ++i) {
        const uint8_t b = data[i];

        result.push_back(digits[b >> 4]);
        result.push_back(digits[b & 0x0F]);
    }

    return result;
}

// Registra apenas uma amostra pequena.
// O programa GXP inteiro continua preservado
// dentro do pacote original descomprimido.
void append_gxp_sample(
    std::string& report,
    const std::string& pack,
    const GxpRecord& rec,
    const std::vector<uint8_t>& data
) {
    constexpr size_t SAMPLE_BYTES = 96;

    const size_t count =
        rec.size < SAMPLE_BYTES
            ? static_cast<size_t>(rec.size)
            : SAMPLE_BYTES;

    const std::string hex = to_hex(
        data.data() + rec.offset,
        count
    );

    report += pack;
    report += "\t";

    report += rec.name;
    report += "\t";

    report += std::to_string(rec.offset);
    report += "\t";

    report += std::to_string(rec.size);
    report += "\t";

    report += std::to_string(count);
    report += "\t";

    report += hex;
    report += "\n";
}

} // namespace

long extract_shaders(
    const nxi::GameImage& game,
    const std::string& out_dir,
    const std::function<void(int, int)>& progress,
    std::string& error
) {
    error.clear();

    if (progress) {
        progress(0, 6);
    }

    // 1. Localizar data.cpk.
    const auto* cpk = game.find("data.cpk");

    if (!cpk || cpk->dir || cpk->size < 16) {
        error =
            "P4G: data.cpk missing or invalid";
        return -1;
    }

    // 2. Identificar os seis pacotes de shaders.
    std::vector<CpkFile> files;

    if (!list_cpk_files(
            game,
            *cpk,
            files,
            error
        )) {
        return -1;
    }

    if (files.size() != 6) {
        error =
            "P4G: unexpected number of GXP packages";
        return -1;
    }

    // 3. Preparar diretorios de saida.
    if (!make_dir(out_dir, error)) {
        return -1;
    }

    const std::string dir =
        out_dir + "/p4g_gxp";

    if (!make_dir(dir, error)) {
        return -1;
    }

    long total = 0;

    std::string summary =
        "pacote\tprogramas\tbytes\n";

    // Relatorio para estudar os cabecalhos GXP.
    // Colunas:
    // pacote
    // nome do programa
    // offset do GXP dentro do pacote
    // tamanho declarado
    // bytes amostrados
    // bytes em hexadecimal
    std::string header_report =
        "pacote\tnome\toffset\t"
        "tamanho\tbytes_amostrados\t"
        "cabecalho_hex\n";

    // 4. Processar cada pacote.
    for (size_t i = 0; i < files.size(); ++i) {
        const CpkFile& file = files[i];

        const size_t slash =
            file.path.find_last_of('/');

        const std::string name =
            slash == std::string::npos
                ? file.path
                : file.path.substr(slash + 1);

        if (!expected_pack_name(name)) {
            error =
                "P4G: unexpected package name: " +
                file.path;

            return -1;
        }

        // 5. Ler e descomprimir o pacote.
        std::vector<uint8_t> data;

        if (!read_cpk_file(
                game,
                *cpk,
                file,
                data,
                error
            )) {
            return -1;
        }

        // 6. Validar estrutura dos registros GXP.
        std::vector<GxpRecord> records;
        std::string parse_error;

        if (!parseGxp(
                data,
                records,
                parse_error
            ) || records.empty()) {
            error =
                "P4G: invalid GXP in " +
                name + ": " + parse_error;

            return -1;
        }

        const char expected_stage =
            name[11] == 'v' ? 'V' : 'F';

        std::string index =
            "nome\toffset\ttamanho\n";

        // 7. Indexar todos os programas.
        for (size_t j = 0;
             j < records.size();
             ++j) {
            const GxpRecord& rec = records[j];

            if (rec.name.empty() ||
                rec.name[0] != expected_stage ||
                rec.offset > data.size() ||
                rec.size > data.size() - rec.offset) {
                error =
                    "P4G: registro GXP invalido em " +
                    name;

                return -1;
            }

            index += rec.name;
            index += "\t";

            index += std::to_string(rec.offset);
            index += "\t";

            index += std::to_string(rec.size);
            index += "\n";

            // Coletar os quatro primeiros
            // cabecalhos de cada pacote.
            //
            // Total maximo: 24 amostras.
            // Nao altera dados nem executa shaders.
            if (j < 4) {
                append_gxp_sample(
                    header_report,
                    name,
                    rec,
                    data
                );
            }
        }

        // 8. Salvar pacote GXP descomprimido.
        if (!save_file(
                dir + "/" + name,
                data.data(),
                data.size(),
                error
            )) {
            return -1;
        }

        // 9. Salvar indice completo dos programas.
        if (!save_file(
                dir + "/" + name + ".tsv",
                reinterpret_cast<const uint8_t*>(
                    index.data()
                ),
                index.size(),
                error
            )) {
            return -1;
        }

        // 10. Atualizar quantidade total.
        if (records.size() >
            static_cast<size_t>(
                std::numeric_limits<long>::max() -
                total
            )) {
            error =
                "P4G: program count "
                "exceeded limit";

            return -1;
        }

        total += static_cast<long>(
            records.size()
        );

        summary += name;
        summary += "\t";

        summary += std::to_string(
            records.size()
        );
        summary += "\t";

        summary += std::to_string(
            data.size()
        );
        summary += "\n";

        if (progress) {
            progress(
                static_cast<int>(i + 1),
                6
            );
        }
    }

    // 11. Salvar resumo dos seis pacotes.
    if (!save_file(
            dir + "/manifest.tsv",
            reinterpret_cast<const uint8_t*>(
                summary.data()
            ),
            summary.size(),
            error
        )) {
        return -1;
    }

    // 12. Salvar diagnostico dos cabecalhos.
    if (!save_file(
            dir + "/gxp_headers.tsv",
            reinterpret_cast<const uint8_t*>(
                header_report.data()
            ),
            header_report.size(),
            error
        )) {
        return -1;
    }

    return total;
}

} // namespace p4g
