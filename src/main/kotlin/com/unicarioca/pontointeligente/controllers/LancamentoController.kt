package com.unicarioca.pontointeligente.controllers

import com.unicarioca.pontointeligente.Response
import com.unicarioca.pontointeligente.documents.Funcionario
import com.unicarioca.pontointeligente.documents.Lancamento
import com.unicarioca.pontointeligente.dtos.LancamentoDto
import com.unicarioca.pontointeligente.enums.PerfilEnum
import com.unicarioca.pontointeligente.enums.TipoEnum
import com.unicarioca.pontointeligente.security.FuncionarioPrincipal
import com.unicarioca.pontointeligente.security.IAuthenticationFacade
import com.unicarioca.pontointeligente.services.FuncionarioService
import com.unicarioca.pontointeligente.services.LancamentoService
import org.apache.commons.lang3.EnumUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.BindingResult
import org.springframework.validation.ObjectError
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.validation.Valid


@RestController
@RequestMapping("/api/lancamentos")
class LancamentoController(val lancamentoService: LancamentoService,
                           val funcionarioService: FuncionarioService) {

    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    @Autowired
    private val authenticationFacade: IAuthenticationFacade? = null

    @Value("\${paginacao.qtd_por_pagina}")
    val qtdPorPagina: Int = 0

    @PostMapping
    fun adicionar(@Valid @RequestBody lancamentoDto: LancamentoDto,
                  result: BindingResult): ResponseEntity<Response<LancamentoDto>> {

        val response: Response<LancamentoDto> = Response<LancamentoDto>()


        validarFuncionario(lancamentoDto, result)

        if (!lancamentoDto.funcionarioId.isNullOrBlank() && !pertenceAoUsuario(lancamentoDto.funcionarioId)) {
            result.addError(ObjectError("funcionario",
                    "Voc?? n??o pode adicionar lan??amentos do funcion??rio " + lancamentoDto.funcionarioId))
        }

        validaTipoLancamento(lancamentoDto.tipo, result)
        validaDataLancamento(lancamentoDto.data, result)

        if (result.hasErrors()) {
            for (error in result.allErrors) error.defaultMessage?.let { response.errors.add(it) }
            return ResponseEntity.badRequest().body(response)
        }

        val lancamento: Lancamento =
                lancamentoService.persistir(converterDtoParaLancamento(lancamentoDto, result))

        response.data = converterLancamentoParaDto(lancamento)
        return ResponseEntity.ok(response)
    }

    @PreAuthorize("hasAnyRole('ADMIN')")
    @PutMapping(value = ["/{id}"])
    fun atualizar(@PathVariable("id") id: String,
                  @Valid @RequestBody lancamentoDto: LancamentoDto,
                  result: BindingResult):
            ResponseEntity<Response<LancamentoDto>> {

        val response: Response<LancamentoDto> = Response<LancamentoDto>()

        val lancamento: Lancamento? = lancamentoService.buscarPorId(id)

        if (lancamento == null) result.addError(ObjectError("lancamento",
                "O Lancamento de id $id n??o foi encontrado."))

        if (lancamentoDto.id != id) result.addError(ObjectError("lancamento",
                "O ID do objeto enviado (${lancamentoDto.id}) ?? diferente do ID passado como par??metro ($id)"))

        validarFuncionario(lancamentoDto, result)
        validaTipoLancamento(lancamentoDto.tipo, result)
        validaDataLancamento(lancamentoDto.data, result)

        if (result.hasErrors()) {
            for (error in result.allErrors) error.defaultMessage?.let { response.errors.add(it) }
            return ResponseEntity.badRequest().body(response)
        }

        var lancamentoAtualizado: Lancamento = converterDtoParaLancamento(lancamentoDto, result)
        lancamentoAtualizado = lancamentoService.persistir(lancamentoAtualizado)

        response.data = converterLancamentoParaDto(lancamentoAtualizado)
        return ResponseEntity.ok(response)
    }

    @GetMapping(value = ["/{id}"])
    fun listarPorId(@PathVariable("id") id: String): ResponseEntity<Response<LancamentoDto>> {
        val response: Response<LancamentoDto> = Response<LancamentoDto>()
        val lancamento: Lancamento? = lancamentoService.buscarPorId(id)

        if (lancamento == null) {
            response.errors.add("Lan??amento $id n??o encontrado")
            return ResponseEntity.badRequest().body(response)
        }

        if (!pertenceAoUsuario(lancamento.funcionarioId)) {
            response.errors.add("Voc?? n??o tem acesso ao Lan??amento $id")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response)
        }

        response.data = converterLancamentoParaDto(lancamento)
        return ResponseEntity.ok(response)
    }

    @GetMapping(value = ["/funcionario/{funcionarioId}"])
    fun listarPorfuncionarioId(@PathVariable("funcionarioId") funcionarioId: String,
                               @RequestParam(value = "pag", defaultValue = "0") pag: Int,
                               @RequestParam(value = "ord", defaultValue = "id") ord: String,
                               @RequestParam(value = "dir", defaultValue = "DESC") dir: String):
            ResponseEntity<Response<Page<LancamentoDto>>> {

        val response: Response<Page<LancamentoDto>> = Response<Page<LancamentoDto>>()

        if (!funcionarioId.isBlank() && !pertenceAoUsuario(funcionarioId)) {
            response.errors.add("Voc?? n??o tem acesso aos Lan??amentos do usu??rio $funcionarioId")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response)
        }

        val direction: String = dir.toUpperCase()

        if (!EnumUtils.isValidEnum(Sort.Direction::class.java, direction)) {
            response.errors.add("Tipo de dire????o n??o suportado. Tipos Suportados: " +
                    Sort.Direction.values().joinToString())
            return ResponseEntity.badRequest().body(response)
        }

        val pageRequest: PageRequest = PageRequest.of(pag, qtdPorPagina, Sort.Direction.valueOf(direction), ord)
        val lancamentos: Page<Lancamento> = lancamentoService.buscarPorFuncionarioId(funcionarioId, pageRequest)

        val lancamentoDto: Page<LancamentoDto> =
                lancamentos.map { lancamento -> converterLancamentoParaDto(lancamento) }

        response.data = lancamentoDto

        return ResponseEntity.ok(response)
    }

    @PreAuthorize("hasAnyRole('ADMIN')")
    @DeleteMapping(value = ["/{id}"])
    fun remover(@PathVariable("id") id: String): ResponseEntity<Response<String>> {
        val response: Response<String> = Response<String>()
        val lancamento: Lancamento? = lancamentoService.buscarPorId(id)

        if (lancamento == null) {
            response.errors.add("Erro ao remover o lan??amento $id. Registro n??o encontrado")
            return ResponseEntity.badRequest().body(response)
        }

        lancamentoService.remover(id)
        response.data = "O lan??amento $id foi removido com sucesso."
        return ResponseEntity.ok(response)
    }


    private fun converterDtoParaLancamento(lancamentoDto: LancamentoDto, result: BindingResult): Lancamento {
        if (lancamentoDto.id != null) {
            val lancamento: Lancamento? = lancamentoService.buscarPorId(lancamentoDto.id)
            if (lancamento == null) result.addError(ObjectError("lancamento",
                    "Lan??amento n??o encontrado."))
        }

        return Lancamento(
                LocalDateTime.parse(lancamentoDto.data, dateTimeFormatter),
                TipoEnum.valueOf(lancamentoDto.tipo),
                lancamentoDto.funcionarioId!!,
                lancamentoDto.descricao,
                lancamentoDto.localizacao,
                lancamentoDto.id
        )
    }

    private fun converterLancamentoParaDto(lancamento: Lancamento?): LancamentoDto =
            LancamentoDto(
                    lancamento?.data.toString(),
                    lancamento?.tipo.toString(),
                    lancamento?.funcionarioId,
                    lancamento?.descricao,
                    lancamento?.localizacao,
                    lancamento?.id
            )

    private fun validarFuncionario(lancamentoDto: LancamentoDto, result: BindingResult) {
        if (lancamentoDto.funcionarioId.isNullOrBlank()) {
            result.addError(ObjectError("funcionario",
                    "Funcion??rio N??o Informado."))
            return
        }

        val funcionario: Funcionario? = funcionarioService.buscarPorId(lancamentoDto.funcionarioId)

        if (funcionario == null) {
            result.addError(ObjectError("funcionario",
                    "Funcion??rio n??o encontrado."))
        }
    }

    private fun validaTipoLancamento(valorEnum: String,
                                     result: BindingResult) {
        if (!EnumUtils.isValidEnum(TipoEnum::class.java,
                        valorEnum)) {
            result.addError(ObjectError("lancamento",
                    "Tipo de lancamento N??o existente. Tipos Suportados: " +
                            TipoEnum.values().joinToString()))
        }
    }

    private fun validaDataLancamento(data: String?, result: BindingResult) {
        try {
            LocalDateTime.parse(data, dateTimeFormatter)
        } catch (e: DateTimeParseException) {
            result.addError(ObjectError("lancamento",
                    "Data do lan??amento fora do padr??o ISO_LOCAL_DATE_TIME"))
        }
    }

    private fun retornaUsuarioLogado(): FuncionarioPrincipal {
        val authentication = authenticationFacade!!.getAuthentication()
        return authentication.principal as FuncionarioPrincipal
    }

    private fun pertenceAoUsuario(funcionarioId: String): Boolean {
        val usuarioLogado = retornaUsuarioLogado()
        if (usuarioLogado.getPerfilFuncionario() == PerfilEnum.ROLE_ADMIN ||
                usuarioLogado.getUserId() == funcionarioId) return true

        return false
    }
}